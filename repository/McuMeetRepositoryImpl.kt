package com.huidiandian.meeting.mcu.repository

import android.Manifest
import androidx.lifecycle.Observer
import com.bifrost.rtcengine.*
import com.bifrost.rtcengine.callback.ISwichCameraListener
import com.bifrost.rtcengine.constants.*
import com.bifrost.rtcengine.entity.*
import com.bifrost.rtcengine.jnienum.*
import com.huidiandian.meeting.base.application.MiguVideoApplicaition
import com.huidiandian.meeting.base.client.QClintManager
import com.huidiandian.meeting.base.constant.LiveDataBusConstantBaseModule
import com.huidiandian.meeting.base.constant.LiveDataBusConstantBaseModule.ON_MEETING_INFO_CHANGED
import com.huidiandian.meeting.base.constant.MMKVConstant
import com.huidiandian.meeting.base.eventmsg.room.webrtc.*
import com.huidiandian.meeting.base.permission.PermissionManager
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.base.util.MMKVUtil
import com.huidiandian.meeting.base.util.NetWorkUtil
import com.huidiandian.meeting.base.util.ToastUtil.toast
import com.huidiandian.meeting.mcu.bean.*
import com.huidiandian.meeting.mcu.util.VolumeProcessor
import com.huidiandian.meeting.meeting.ScreenShare.QScreenShareUtils
import com.huidiandian.meeting.meeting.room.meet.MeetingActivity.Companion.CODE_REQUEST_CAPTURE
import com.jeremyliao.liveeventbus.LiveEventBus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow

class McuMeetRepositoryImpl(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : IMcuMeetRepository {

    private val tag = "McuMeetRepo"
    private val volumeProcessor = VolumeProcessor(100, 0.3f, 5)
    private val _state = MutableStateFlow(McuMeetState())
    override val state: StateFlow<McuMeetState> = _state
    private val _localMicVolume = MutableStateFlow(0)
    override val localMicVolume = _localMicVolume
    private val coroutineScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val selfAccount by lazy { MMKVUtil.decodeString(MMKVConstant.LOGIN_ACCOUNT, "") }
    private val reconnectMap = ConcurrentHashMap<String, ReconnectInfo>()
    private val pullReconnectMap = ConcurrentHashMap<String, ReconnectInfo>()
    private var currentReconnectJob: Job? = null
    private var currentPullReconnectJob: Job? = null
    private var currentPullChannelId: String? = null
    private var hasSubscribed = false

    // 统一事件监听注册/注销
    private val liveEventObservers = mutableListOf<Pair<String, Observer<*>>>()

    private fun <T : Any> observeEvent(eventKey: String, clazz: Class<T>, observer: Observer<T>) {
        LiveEventBus.get(eventKey, clazz).observeForever(observer)
        liveEventObservers.add(eventKey to observer)
        AppLogger.getInstance().d("$tag observeEvent: $eventKey")
    }

    private fun removeAllObservers() {
        AppLogger.getInstance().d("$tag removeAllObservers")
        liveEventObservers.forEach { (key, observer) ->
            @Suppress("UNCHECKED_CAST")
            LiveEventBus.get(key, Any::class.java).removeObserver(observer as Observer<Any>)
        }
        liveEventObservers.clear()
    }

    // 状态安全更新
    private inline fun updateState(action: (McuMeetState) -> McuMeetState) {
        _state.update(action)
    }

    // 推流/拉流通道状态统一处理
    private fun handleChannelStateUpdate(msg: ChannelStateUpdateMessage) {
        val isPull = msg.channelId == QRtcEngine.get().pullChannelId
        if (!isPull) handlePushChannelState(msg) else handlePullChannelState(msg)
    }

    private fun handlePushChannelState(msg: ChannelStateUpdateMessage) {
        when (msg.state) {
            QChannelState.NEW, QChannelState.CHECKING -> updateState { it.copy(publishStreamState = PublishStreamState.CONNECTING) }
            QChannelState.CONNECTED, QChannelState.COMPLETED -> {
                updateState {
                    it.copy(
                        publishStreamState = PublishStreamState.CONNECTED,
                        isConnected = true
                    )
                }
                reconnectMap.remove(msg.channelId)
                currentReconnectJob?.cancel()
            }

            QChannelState.FAILED, QChannelState.DISCONNECTED -> {
                updateState {
                    it.copy(
                        publishStreamState = PublishStreamState.FAILED,
                        isConnected = false
                    )
                }
                handlePushReconnect(msg.channelId)
            }

            QChannelState.CLOSED -> {
                updateState { it.copy(publishStreamState = PublishStreamState.IDLE) }
                reconnectMap.remove(msg.channelId)
                currentReconnectJob?.cancel()
            }
        }
    }

    private fun handlePullChannelState(msg: ChannelStateUpdateMessage) {
        AppLogger.getInstance().d("$tag handlePullChannelState: $msg")
        when (msg.state) {
            QChannelState.NEW, QChannelState.CHECKING -> updateState { it.copy(pullStreamState = PullStreamState.CHECKING) }
            QChannelState.CONNECTED -> {
                updateState { it.copy(pullStreamState = PullStreamState.CONNECTED) }
                pullReconnectMap.remove(msg.channelId)
                currentPullReconnectJob?.cancel()
                startPullingStream(msg.channelId)
            }

            QChannelState.FAILED -> {
                updateState { it.copy(pullStreamState = PullStreamState.FAILED) }
                handlePullReconnect(msg.channelId, PullChannelFailureType.CHANNEL_FAILED)
            }

            QChannelState.DISCONNECTED -> {
                updateState { it.copy(pullStreamState = PullStreamState.DISCONNECTED) }
                handlePullReconnect(msg.channelId, PullChannelFailureType.DISCONNECTED)
            }

            QChannelState.CLOSED -> {
                updateState { it.copy(pullStreamState = PullStreamState.CLOSED) }
                pullReconnectMap.remove(msg.channelId)
                currentPullReconnectJob?.cancel()
                currentPullChannelId = null
                hasSubscribed = false
            }

            else -> {}
        }
    }

    override fun applyOpenMic(): Result<Int> =
        QRtcEngine.get().applyOpenMic().let { code ->
            if (code == QErrorCode.SUCCESS.value()) Result.Success(code)
            else Result.Error(Exception("申请麦克风失败: $code"), code)
        }

    override suspend fun toggleMicrophone(): Result<Boolean> = withContext(ioDispatcher) {
        val current = state.value.isMicEnabled
        val newState = !current
        val code = QRtcEngine.get().setMicrophoneState(selfAccount, newState)
        if (code != 0) return@withContext Result.Error(Exception("设置麦克风失败: $code"))
        QRtcEngine.get().enableMicrophone(QStreamType.MAIN, newState)
        updateState { it.copy(isMicEnabled = newState) }
        Result.Success(newState)
    }

    override suspend fun toggleCamera(): Result<Boolean> = withContext(ioDispatcher) {
        if (!QRtcEngine.get().isLocalCameraPreview(QStreamType.MAIN)) startPreview(QStreamType.MAIN)
        updateState { it.copy(isPreview = true) }
        val current = state.value.isCameraEnabled
        val newState = !current
        val code = QRtcEngine.get().setCameraState(selfAccount, newState)
        if (code != QErrorCode.SUCCESS.value()) return@withContext Result.Error(Exception("设置摄像头失败: $code"))
        QRtcEngine.get().enableCamera(QStreamType.MAIN, newState)
        updateState { it.copy(isCameraEnabled = newState) }
        Result.Success(newState)
    }

    override suspend fun startScreenSharing(): Result<Unit> = withContext(ioDispatcher) {
        if (!checkScreenCapturePermission()) {
            LiveEventBus.get<Unit>(LiveDataBusConstantBaseModule.SCREEN_CAPTURE_PERMISSION_REQUIRED)
                .post(Unit)
            return@withContext Result.Error(Exception("需要屏幕录制权限"))
        }
        val code = QRtcEngine.get().setShareState(
            selfAccount,
            selfAccount,
            true,
            EnMeetShareType.MEET_SHARE_TYPE_SCREENSHARE,
            ""
        )
        if (code != 0) return@withContext Result.Error(Exception("开始屏幕共享失败: $code"))
        updateState { it.copy(isSelfSharing = true) }
        Result.Success(Unit)
    }

    override suspend fun stopScreenSharing(): Result<Unit> = withContext(ioDispatcher) {
        val code = QRtcEngine.get().setShareState(
            selfAccount,
            selfAccount,
            false,
            EnMeetShareType.MEET_SHARE_TYPE_SCREENSHARE,
            ""
        )
        QRtcEngine.get().stopPublishStream(
            selfAccount,
            QRtcEngine.get().meetingInfo.room.id,
            QStreamType.SHARE,
            true
        )
        if (code != 0) return@withContext Result.Error(Exception("停止屏幕共享失败: $code"))
        updateState { it.copy(isSelfSharing = false) }
        Result.Success(Unit)
    }

    override suspend fun refreshParticipants(): Result<List<ParticipantData>> =
        withContext(ioDispatcher) {
            updateState { it.copy(isLoading = true) }
            val participants =
                QRtcEngine.get().getPagesOfParticipants(0, 20, true).info ?: emptyList()
            val data = participants.map {
                ParticipantData(
                    it.userInfo.account, it.userInfo.name, it.userInfo.phone,
                    it.role, it.micStatus, it.cameraStatus, it.userInfo.account == selfAccount
                )
            }
            updateState { it.copy(isLoading = false, participants = data) }
            Result.Success(data)
        }

    override fun captureScreen() {
        QScreenShareUtils.grantScreenCapturePermission(
            MiguVideoApplicaition.getContext().applicationContext, CODE_REQUEST_CAPTURE
        )
    }

    override suspend fun setupStream(): Result<Unit> =
        withContext(ioDispatcher) {
            getSelfInfo().onSuccess { info ->
                pushStream(info?.cameraStatus == true, info?.micStatus == true)
            }
            createPullChannel()
            Result.Success(Unit)
        }

    override fun getPublishers(): ArrayList<QParticipant>? =
        QRtcEngine.get().allPublishers.info?.publishers

    override suspend fun switchCamera(): Result<Unit> = withContext(ioDispatcher) {
        val deferred = CompletableDeferred<Result<Unit>>()
        QRtcEngine.get().switchCamera(object : ISwichCameraListener {
            override fun onSuccess() {
                deferred.complete(Result.Success(Unit))
            }

            override fun onFailed(code: QErrorCode?, msg: String?) {
                deferred.complete(Result.Error(Exception("切换摄像头失败: $code, $msg")))
            }
        })
        deferred.await()
    }

    // 推流/拉流核心方法  todo 重复推流
    private fun pushStream(enableVideo: Boolean, enableAudio: Boolean) {
        updateState { it.copy(publishStreamState = PublishStreamState.CONNECTING) }
        val result = QRtcEngine.get().startPublishStream(StartPublishParams().apply {
            streamType = QStreamType.MAIN
            audioState = if (enableAudio) QDeviceState.OPEN else QDeviceState.MUTE
            videoState = if (enableVideo) QDeviceState.OPEN else QDeviceState.MUTE
        })
        if (result.isNotEmpty()) AppLogger.getInstance().d("$tag pushStream: $result")
        else AppLogger.getInstance().d("$tag pushStream: null")
    }

    private fun createPullChannel() {
        updateState { it.copy(pullStreamState = PullStreamState.CREATING) }
        hasSubscribed = false
        val result = QRtcEngine.get().createChannel(true)
        AppLogger.getInstance().d("$tag createPullChannel : $result")
        if (result.isNotEmpty()) currentPullChannelId = result
        else AppLogger.getInstance().d("$tag createPullChannel: null")
    }

    private fun startPullingStream(channelId: String) {
        AppLogger.getInstance()
            .d("$tag startPullingStream: $channelId  , hasSubscribed = $hasSubscribed")
        if (hasSubscribed) return
        updateState { it.copy(pullStreamState = PullStreamState.SUBSCRIBING) }
        coroutineScope.launch(ioDispatcher) {
            val roomId = QRtcEngine.get().meetingInfo.room?.id ?: return@launch
            val result = QRtcEngine.get()
                .subscribeStream(channelId, roomId, QStreamParams("", QStreamType.MCU, true, true))
            AppLogger.getInstance().d("$tag subscribeStream: $result")
            if (result == QErrorCode.SUCCESS.ordinal) {
                hasSubscribed = true
                updateState { it.copy(pullStreamState = PullStreamState.STREAMING) }
            }
        }
    }

    // 通用重连处理
    private fun handlePushReconnect(channelId: String) {
        launchReconnect(
            jobRef = { currentReconnectJob },
            setJob = { currentReconnectJob = it },
            map = reconnectMap,
            key = channelId,
            delayCalc = { info -> calculateReconnectDelay(info) }
        ) {
            stopPushStream()
            delay(500)
            pushStream(state.value.isCameraEnabled, state.value.isMicEnabled)
        }
    }

    private fun handlePullReconnect(channelId: String, type: PullChannelFailureType) {
        launchReconnect(
            jobRef = { currentPullReconnectJob },
            setJob = { currentPullReconnectJob = it },
            map = pullReconnectMap,
            key = channelId,
            delayCalc = { info -> calculateReconnectDelay(info, type) }
        ) {
            stopPullingStream()
            delay(500)
            createPullChannel()
        }
    }

    private fun launchReconnect(
        jobRef: () -> Job?,
        setJob: (Job) -> Unit,
        map: ConcurrentHashMap<String, ReconnectInfo>,
        key: String,
        delayCalc: (ReconnectInfo) -> Long,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        jobRef()?.cancel()
        val info = map.getOrPut(key) { ReconnectInfo(AtomicInteger(0), System.currentTimeMillis()) }
        val delay = delayCalc(info)
        info.attemptCount.incrementAndGet()
        map[key] = info
        setJob(coroutineScope.launch { delay(delay); block() })
    }

    private fun calculateReconnectDelay(
        info: ReconnectInfo,
        type: PullChannelFailureType? = null,
    ): Long {
        val ctx = MiguVideoApplicaition.getContext()
        val isNetworkGood = NetWorkUtil.isNetworkAvailable(ctx)
        val networkType = NetWorkUtil.getNetworkType(ctx)
        val baseDelay = when {
            !isNetworkGood -> 5000L
            type == PullChannelFailureType.CREATE_FAILED -> 1000L
            networkType == NetWorkUtil.NETWORK_WIFI -> 300L
            else -> 800L
        }
        val attempt = info.attemptCount.get()
        return if (attempt >= 10) 30000L else min(
            baseDelay * 2.0.pow(attempt.toDouble()).toLong(),
            30000L
        )
    }

    private fun stopPushStream() {
        AppLogger.getInstance().d("$tag stopPushStream")
        QRtcEngine.get().stopPublishStream(
            selfAccount,
            QRtcEngine.get().meetingInfo.room?.id,
            QStreamType.MAIN,
            true
        )
    }

    private fun stopPullingStream() {
        AppLogger.getInstance().d("$tag stopPullingStream: $currentPullChannelId")
        QRtcEngine.get().destroyChannel(currentPullChannelId)
        currentPullChannelId = null
        hasSubscribed = false
    }

    override fun checkScreenCapturePermission(): Boolean {
        val ctx = MiguVideoApplicaition.getContext().applicationContext
        return PermissionManager.isGranted(ctx, Manifest.permission.SYSTEM_ALERT_WINDOW)
                && PermissionManager.isGranted(ctx, Manifest.permission.RECORD_AUDIO)
    }

    override fun startPreview(streamType: QStreamType): Result<Unit> = try {
        QRtcEngine.get().startPreview(StartPreviewParams().apply { this.streamType = streamType })
        updateState { it.copy(isPreview = true) }
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e)
    }

    override fun stopPreview(streamType: QStreamType): Result<Unit> = try {
        AppLogger.getInstance().d("$tag stopPreview: $streamType")
        QRtcEngine.get().stopPreview(streamType)
        updateState { it.copy(isPreview = false) }
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e)
    }

    override fun enableMicrophone(streamType: QStreamType, enabled: Boolean): Result<Unit> = try {
        AppLogger.getInstance().d("$tag enableMicrophone: $streamType, $enabled")
        QRtcEngine.get().enableMicrophone(streamType, enabled)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(e)
    }

    override suspend fun getSelfInfo(): Result<QParticipant?> = withContext(ioDispatcher) {
        Result.Success(QRtcEngine.get().getParticipant(selfAccount).info)
    }

    override suspend fun getMeetingInfo(): QRoom {
        val info = QRtcEngine.get().meetingInfo
        updateState { it.copy(meetingInfo = info) }
        return info
    }

    override fun leaveRoom() {
        QRtcEngine.get().leaveMeeting(QRtcEngine.get().meetingInfo.room.id)
    }

    override fun closeRoom() {
        QRtcEngine.get().closeMeeting(QRtcEngine.get().meetingInfo.room.id)
    }

    override fun setStreamStatsInterval(num: Int) {
        QRtcEngine.get().setStreamStatsInterval(num)
    }

    override fun switchVoiceMode(enabled: Boolean) {
        QClintManager.getInstance().switchVoiceMode(if (enabled) 1 else 0)
    }

    fun checkReconnectStatus(): RecordState {
        val recordInfo =
            QRtcEngine.get().getConferenceRecordStatus(_state.value.meetingInfo?.room?.id)
        return if (recordInfo.boolVar) {
            updateState {
                it.copy(
                    recordingState = RecordState(
                        true,
                        startTime = recordInfo.longNumber
                    )
                )
            }
            RecordState(true, startTime = recordInfo.longNumber)
        } else {
            updateState { it.copy(recordingState = RecordState(false)) }
            RecordState(false)
        }
    }

    override fun release() {
        AppLogger.getInstance().d("$tag release")
        QRtcEngine.get().stopListenSoundLevel()
        stopPushStream()
        stopPullingStream()
        stopPreview(QStreamType.MAIN)
        removeAllObservers()
        currentReconnectJob?.cancel()
        currentPullReconnectJob?.cancel()
        reconnectMap.clear()
        pullReconnectMap.clear()
        currentPullChannelId = null
        hasSubscribed = false
        coroutineScope.cancel()
        AppLogger.getInstance().d("$tag release end")
    }

    init {
        QRtcEngine.get().startListenSoundLevel()
        coroutineScope.launch {
            getSelfInfo().onSuccess { info ->
                info?.let {
                    updateState {
                        it.copy(
                            isSelfSharing = info.shareStatus,
                            isLecture = info.lectureStatus,
                            isVoiceMode = info.isVoiceMode,
                            isHand = info.handStatus,
                            isMicEnabled = info.micStatus,
                            isCameraEnabled = info.cameraStatus,
                            userInfo = info
                        )
                    }
                }
            }
            getMeetingInfo().let { meetingInfo ->
                updateState {
                    it.copy(
                        isHost = meetingInfo.hostInfo.account == selfAccount,
                        roomId = meetingInfo.room.id
                    )
                }
            }
            checkReconnectStatus()
        }

        // 注册所有事件监听
        observeEvent(
            LiveDataBusConstantBaseModule.ON_CHANNEL_STATE_UPDATE,
            ChannelStateUpdateMessage::class.java,
            Observer { handleChannelStateUpdate(it) })
        observeEvent(
            LiveDataBusConstantBaseModule.ON_SET_SHARE_STATE,
            OnSetShareState::class.java,
            Observer { msg ->
                updateState {
                    it.copy(
                        isSharing = msg.shareState,
                        isSelfSharing = msg.shareAccount == selfAccount && msg.shareState,
                        shareAccount = if (msg.shareState) msg.shareAccount else ""
                    )
                }
            })
        observeEvent(
            LiveDataBusConstantBaseModule.ON_AUDIO_IN_STATUS,
            OnAudioInStatus::class.java,
            Observer { msg ->
                if (msg.accountTo == selfAccount) updateState { it.copy(isMicEnabled = msg.status) }
            })
        observeEvent(
            LiveDataBusConstantBaseModule.ON_MICROPHONE_SOUND_LEVEL,
            OnMicrophoneSoundLevel::class.java,
            Observer { msg ->
                _localMicVolume.value = volumeProcessor.processVolume(msg.soundLevel)
            })
        observeEvent(
            LiveDataBusConstantBaseModule.ON_VIDEO_STATUS,
            OnVideoStatus::class.java,
            Observer { msg ->
                if (msg.accountTo == selfAccount) updateState { it.copy(isCameraEnabled = msg.status) }
            })
        observeEvent(
            LiveDataBusConstantBaseModule.ON_MEETING_CLOSED,
            OnMeetingClosed::class.java,
            Observer { msg ->
                updateState { it.copy(roomCloseReason = msg.reason) }
            })
        observeEvent(
            LiveDataBusConstantBaseModule.ON_NOTIFY_PART_ROLE_CHANGED,
            OnNotifyPartRoleChanged::class.java,
            Observer { msg ->
                if (msg.account == selfAccount) updateState {
                    it.copy(
                        role = QRoomRole.fromOrdinal(
                            msg.roleNew
                        )
                    )
                }
            })
        observeEvent(
            LiveDataBusConstantBaseModule.DISCARD_VIDEO_STREAM_STATUS_CHANGE_NOTIFY,
            DiscardVideoStreamStatusChangeNotify::class.java,
            Observer { msg ->
                QRtcEngine.get().pauseCamera(
                    QStreamType.fromOrdinal(msg.streamType),
                    msg.discardVideoStreamStatus
                )
            })
        observeEvent(
            LiveDataBusConstantBaseModule.ON_SWITCH_VOICE_MODE_NOTIFY,
            OnSwitchVoiceModeNotify::class.java,
            Observer { msg ->
                AppLogger.getInstance()
                    .d("$tag onSwitchVoiceModeNotify: ${msg.operation} ${msg.operation == QSwitch.ON.ordinal}")
                updateState { it.copy(isVoiceMode = msg.operation == QSwitch.ON.ordinal) }
            })
        observeEvent(
            LiveDataBusConstantBaseModule.ON_START_CONFERENCE_RECORD,
            OnStartConferenceRecordMessage::class.java,
            Observer { checkReconnectStatus() })
        observeEvent(
            LiveDataBusConstantBaseModule.ON_STOP_CONFERENCE_RECORD,
            OnStopConferenceRecordMessage::class.java,
            Observer { msg ->
                updateState { it.copy(recordingState = RecordState(false, msg.reason)) }
            })
        observeEvent(ON_MEETING_INFO_CHANGED, OnMeetingInfoChanged::class.java, Observer { msg ->
            if (msg.option == QMeetingInfoOption.MEETINGINFO_OPTION_muteMode) {
                if (msg.meeting.joinRoomMuteMode == QJoinRoomMuteMode.JOINROOM_MUTE_SMART && msg.meeting.hostInfo?.account != selfAccount) {
                    toast("主持人已解除全体静音")
                } else if (msg.meeting.joinRoomMuteMode == QJoinRoomMuteMode.JOINROOM_MUTE_CLOSE_ALL_MIC && msg.meeting.hostInfo?.account != selfAccount) {
                    toast("主持人已设置全体静音")
                }
            }
            coroutineScope.launch(ioDispatcher) { getMeetingInfo() }
        })
    }

    data class ReconnectInfo(val attemptCount: AtomicInteger, var lastAttemptTime: Long)
}