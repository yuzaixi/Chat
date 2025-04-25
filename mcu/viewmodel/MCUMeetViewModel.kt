package com.huidiandian.meeting.mcu.viewmodel

import android.Manifest
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bifrost.rtcengine.QRtcEngine
import com.bifrost.rtcengine.constants.QRoomRole
import com.bifrost.rtcengine.constants.QStreamType
import com.bifrost.rtcengine.jnienum.EnMeetShareType
import com.huidiandian.meeting.base.application.MiguVideoApplicaition
import com.huidiandian.meeting.base.enum.ErrorCode
import com.huidiandian.meeting.base.permission.OnHHDPermissionCallback
import com.huidiandian.meeting.base.permission.PermissionManager
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.base.util.ToastUtil.toast
import com.huidiandian.meeting.mcu.bean.ErrorHandler
import com.huidiandian.meeting.mcu.bean.McuMeetEvent
import com.huidiandian.meeting.mcu.bean.McuMeetState
import com.huidiandian.meeting.mcu.bean.Result
import com.huidiandian.meeting.mcu.bean.updateSafely
import com.huidiandian.meeting.mcu.repository.IMcuMeetRepository
import com.huidiandian.meeting.meeting.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MCU 会议界面的 ViewModel。
 * 负责处理用户交互、管理会议状态、与 Repository 交互以及处理权限和媒体逻辑。
 */
class MCUMeetViewModel(
    private val repository: IMcuMeetRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate, // 使用 immediate 避免不必要延迟
) : ViewModel() {
    private val tag = "MCUMeetViewModel"
    private val maxMicOpenSize = 6 // 最大允许同时开启麦克风的数量

    // --- 状态管理 ---
    private val _viewState = MutableStateFlow(McuMeetState())
    val viewState = _viewState.asStateFlow() // UI 观察的状态流

    private val _localMicVolume = MutableStateFlow(0)
    val localMicVolume = _localMicVolume.asStateFlow() // 本地麦克风音量状态流

    // --- 事件通道 ---
    private val _events = Channel<McuMeetEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow() // UI 观察的单次事件流

    private val appContext: Context
        get() = MiguVideoApplicaition.getContext().applicationContext

    init {
        observeRepositoryState()
        observeMicVolume()
    }

    // ### 状态观察与基础方法 ###

    /**
     * 观察 Repository 的状态流，并将其更新到 ViewModel 的 viewState。
     */
    private fun observeRepositoryState() {
        repository.state
            .onEach { repoState ->
                // 在主线程安全地更新状态
                withContext(mainDispatcher) {
                    _viewState.updateSafely { currentState ->
                        currentState.copy(
                            isMicEnabled = repoState.isMicEnabled,
                            isCameraEnabled = repoState.isCameraEnabled,
                            isSharing = repoState.isSharing,
                            isSelfSharing = repoState.isSelfSharing,
                            isLoading = repoState.isLoading,
                            publishStreamState = repoState.publishStreamState,
                            pullStreamState = repoState.pullStreamState,
                            participants = repoState.participants,
                            error = repoState.error,
                            shareAccount = repoState.shareAccount,
                            roomId = repoState.roomId,
                            isHost = repoState.isHost,
                            isLecture = repoState.isLecture,
                            isHand = repoState.isHand,
                            isVoiceMode = repoState.isVoiceMode,
                            isFullscreen = repoState.isFullscreen,
                            roomCloseReason = repoState.roomCloseReason,
                            meetingInfo = repoState.meetingInfo,
                            role = repoState.role,
                            userInfo = repoState.userInfo,
                            recordingState = repoState.recordingState,
                            isPreview = repoState.isPreview,
                            memberNumbers = repoState.memberNumbers,
                        )
                    }
                }
            }
            .launchIn(viewModelScope) // 在 viewModelScope 中启动收集
    }

    /**
     * 观察 Repository 的本地麦克风音量流。
     */
    private fun observeMicVolume() {
        repository.localMicVolume
            .onEach { volume ->
                // 麦克风音量更新通常不直接影响 UI 状态，可以直接赋值
                _localMicVolume.value = volume
            }
            .launchIn(viewModelScope)
    }

    /**
     * 安全地发送单次事件到 UI 层。
     * @param event 要发送的事件。
     */
    private fun sendEvent(event: McuMeetEvent) {
        // 确保在 viewModelScope 中启动，默认主线程
        viewModelScope.launch {
            _events.send(event)
        }
    }

    /** 记录 Info 级别日志 */
    private fun logInfo(message: String) {
        AppLogger.getInstance().d("$tag: $message")
    }

    /** 记录 Error 级别日志 */
    private fun logError(message: String, e: Throwable? = null) {
        AppLogger.getInstance().e("$tag: $message${e?.let { " : error: ${it.message}" } ?: ""}")
    }

    // ### 房间管理 ###

    /**
     * 检查当前会议房间状态，确保房间 ID 有效。
     * 如果无效，则发送错误提示并结束 Activity。
     */
    fun checkRoomStatus() {
        viewModelScope.launch { // 检查操作通常很快，可以在主线程
            val room = withContext(ioDispatcher) { QRtcEngine.get().meetingInfo.room } // 获取信息可能耗时
            if (room?.id.isNullOrEmpty()) {
                logError("房间ID为空或不存在，无法继续会议")
                sendEvent(McuMeetEvent.ShowToast("会议不存在或已结束"))
                sendEvent(McuMeetEvent.FinishRoom)
            } else {
                logInfo("房间ID校验成功: ${room.id}")
                _viewState.update { it.copy(roomId = room.id) }
            }
        }
    }

    /**
     * 触发显示离开或结束会议的确认对话框。
     * 根据用户角色决定对话框类型。
     */
    fun leaveOrCloseRoom() {
        viewModelScope.launch {
            val isHost = withContext(ioDispatcher) {
                repository.getSelfInfo().successDataOrNull()?.role == QRoomRole.HOST.ordinal
            }
            logInfo("请求离开或关闭房间，是否为主持人: $isHost")
            sendEvent(McuMeetEvent.ShowLeaveOrCloseDialog(isHost))
        }
    }

    /**
     * 执行离开房间的操作。
     */
    fun leaveRoom() {
        viewModelScope.launch {
            withContext(ioDispatcher) { repository.leaveRoom() }
            logInfo("已离开房间")
            sendEvent(McuMeetEvent.FinishRoomAndJumpDuration)
        }
    }

    /**
     * 执行关闭房间的操作（仅主持人）。
     */
    fun closeRoom() {
        viewModelScope.launch {
            withContext(ioDispatcher) { repository.closeRoom() }
            logInfo("已关闭房间")
            sendEvent(McuMeetEvent.FinishRoomAndJumpDuration)
        }
    }

    // ### 权限管理 ###

    /**
     * 检查并请求相机和麦克风权限。
     * @param context Context 对象，用于请求权限。
     */
    fun checkMediaPermissions(context: Context) {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val granted = PermissionManager.isGranted(context, permissions)

        if (granted) {
            logInfo("已拥有所有媒体权限")
            _viewState.update {
                it.copy(
                    permissionState = it.permissionState.copy(
                        hasCamera = true,
                        hasMicrophone = true
                    )
                )
            }
            initializeMedia() // 初始化媒体设备
            return
        }

        logInfo("请求媒体权限: ${permissions.joinToString()}")
        PermissionManager.with(context)
            .permission(permissions, object : OnHHDPermissionCallback {
                override fun onGranted(grantedPermissions: List<String>, all: Boolean) {
                    val hasCamera =
                        grantedPermissions.contains(Manifest.permission.CAMERA) || PermissionManager.isGranted(
                            context,
                            Manifest.permission.CAMERA
                        )
                    val hasMic =
                        grantedPermissions.contains(Manifest.permission.RECORD_AUDIO) || PermissionManager.isGranted(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        )
                    logInfo("权限授予回调: 相机=$hasCamera, 麦克风=$hasMic, 是否全部授予=$all")
                    _viewState.update {
                        it.copy(
                            permissionState = it.permissionState.copy(
                                hasCamera = hasCamera,
                                hasMicrophone = hasMic
                            )
                        )
                    }
                    initializeMedia()
                }

                override fun onDenied(deniedPermissions: List<String>, never: Boolean) {
                    val hasCamera = PermissionManager.isGranted(context, Manifest.permission.CAMERA)
                    val hasMic =
                        PermissionManager.isGranted(context, Manifest.permission.RECORD_AUDIO)
                    logInfo("权限拒绝回调: 相机=$hasCamera, 麦克风=$hasMic, 是否永久拒绝=$never")
                    _viewState.update {
                        it.copy(
                            permissionState = it.permissionState.copy(
                                hasCamera = hasCamera,
                                hasMicrophone = hasMic
                            )
                        )
                    }
                    initializeMedia() // 即使拒绝，也尝试初始化已有的权限
                    if (never) {
                        sendEvent(McuMeetEvent.ShowToast("部分核心权限被永久拒绝，请前往设置开启"))
                    } else if (!hasCamera || !hasMic) {
                        sendEvent(McuMeetEvent.ShowToast("缺少相机或麦克风权限，功能可能受限"))
                    }
                }
            })
    }

    /**
     * 检查屏幕捕捉权限（实际是悬浮窗权限）。
     * @return true 如果有权限，false 如果没有。
     */
    fun checkScreenCapturePermission(): Boolean = repository.checkScreenCapturePermission()

    // ### 媒体控制 ###

    /**
     * 根据当前权限状态初始化媒体设备（预览和麦克风）。
     */
    private fun initializeMedia() {
        viewModelScope.launch(ioDispatcher) { // repository 操作可能耗时
            val state = viewState.value.permissionState
            logInfo("初始化媒体：相机权限=${state.hasCamera}, 麦克风权限=${state.hasMicrophone} , isCameraEnabled=${viewState.value.isCameraEnabled}, isMicEnabled=${viewState.value.isMicEnabled}")
            repository.getSelfInfo().onSuccess { participant ->
                if (state.hasCamera && participant?.cameraStatus == true) {
                    logInfo("初始化媒体：启动摄像头预览")
                    repository.startPreview(QStreamType.MAIN) // 假设 startPreview 会通过 state 更新 isPreview
                }
                if (state.hasMicrophone && participant?.micStatus == true) {
                    logInfo("初始化媒体：启用麦克风")
                    repository.enableMicrophone(
                        QStreamType.MAIN,
                        true
                    ) // 假设 enableMicrophone 会通过 state 更新 isMicEnabled
                }
            }
        }
    }

    fun enableMicrophone() {
        AppLogger.getInstance().d("$tag: 启用麦克风")
        viewModelScope.launch(ioDispatcher) {
            repository.enableMicrophone(QStreamType.MAIN, true)
        }
    }

    /**
     * 切换本地麦克风的启用/禁用状态。
     * 会进行权限、角色、会议设置和人数限制的检查。
     */
    fun toggleMicrophone() {
        viewModelScope.launch {
            val prerequisitesMet = checkMicPrerequisites() // 在 IO 线程检查前提条件
            if (!prerequisitesMet) return@launch // 条件不满足则直接返回

            // 执行切换操作
            val result = withContext(ioDispatcher) { repository.toggleMicrophone() }

            // 处理结果
            result.fold(
                onSuccess = { enabled ->
                    logInfo("麦克风状态切换成功: ${if (enabled) "开启" else "关闭"}")
                    sendEvent(McuMeetEvent.ShowToast(appContext.getString(if (enabled) R.string.status_microphone_on else R.string.status_microphone_off)))
                },
                onError = { error, code ->
                    logError("切换麦克风失败", error)
                    if (code == ErrorCode.CODE_MEET_AUDIO_UPPER_LIMIT.code) {
                        // 根据角色提示不同信息
                        if (viewState.value.role != QRoomRole.HOST) {
                            sendEvent(McuMeetEvent.ShowDialog(McuMeetEvent.DIALOG_TYPE_MIC_BAN)) // 提示被禁言或申请
                        } else {
                            sendEvent(McuMeetEvent.ShowToast(appContext.getString(R.string.error_microphone_limit_reached))) // 主持人提示超限
                        }
                    } else {
                        handleError(error, "切换麦克风失败") // 通用错误处理
                    }
                }
            )
        }
    }

    /**
     * 检查切换麦克风的前提条件（权限、角色、会议设置、人数限制）。
     * @return true 如果满足所有条件，false 如果不满足。
     */
    private suspend fun checkMicPrerequisites(): Boolean = withContext(ioDispatcher) {
        val state = viewState.value
        if (!state.permissionState.hasMicrophone) {
            logInfo("切换麦克风检查：无麦克风权限")
            sendEvent(McuMeetEvent.ShowToast("需要麦克风权限"))
            return@withContext false
        }

        val selfInfo = repository.getSelfInfo().successDataOrNull()
        val roomInfo = repository.getMeetingInfo() // 假设这个方法不耗时或已缓存

        if (selfInfo == null) {
            logError("切换麦克风检查：无法获取个人信息")
            return@withContext false
        }

        // 观众不能直接开关麦克风
        if (selfInfo.role == QRoomRole.AUDIENCE.ordinal) {
            logInfo("切换麦克风检查：观众角色，提示举手")
            sendEvent(McuMeetEvent.ShowToast(appContext.getString(com.huidiandian.meeting.base.R.string.base_str_please_hand_up_to_speak)))
            return@withContext false
        }

        // 非主持人，且会议不允许成员开麦，且当前麦克风是关闭状态（尝试开启时检查）
        if (selfInfo.role != QRoomRole.HOST.ordinal && !roomInfo.allowPartOpenMic && !state.isMicEnabled) {
            logInfo("切换麦克风检查：非主持人，会议不允许开麦，且尝试开启")
            sendEvent(McuMeetEvent.ShowDialog(McuMeetEvent.DIALOG_TYPE_MIC_BAN))
            return@withContext false
        }

        // 尝试开启麦克风时，检查是否达到人数上限
        if (!state.isMicEnabled) {
            val micCount = repository.getPublishers()?.count { it.micStatus } ?: 0
            if (micCount >= maxMicOpenSize) {
                logInfo("切换麦克风检查：尝试开启，但已达人数上限 ($micCount >= $maxMicOpenSize)")
                sendEvent(McuMeetEvent.ShowToast(appContext.getString(R.string.warning_too_many_microphones_affecting_meeting)))
                // 注意：这里不直接返回 false，让后续 toggleMicrophone 尝试，如果 RTC 层也有限制会返回错误码
            }
        }
        return@withContext true // 所有检查通过
    }

    /**
     * 切换本地摄像头的启用/禁用状态。
     * 会进行权限和语音模式检查。
     */
    fun toggleCamera() {
        viewModelScope.launch {
            val state = viewState.value
            if (!state.permissionState.hasCamera) {
                logInfo("切换摄像头：无相机权限")
                sendEvent(McuMeetEvent.PermissionRequired(Manifest.permission.CAMERA))
                return@launch
            }
            if (state.isVoiceMode) {
                logInfo("切换摄像头：语音模式下不可用")
                sendEvent(McuMeetEvent.ShowToast(appContext.getString(R.string.error_camera_unavailable_in_voice_mode)))
                return@launch
            }

            logInfo("执行切换摄像头操作")
            withContext(ioDispatcher) {
                repository.toggleCamera().onSuccess { isCameraEnabled ->
                    toast(
                        appContext.getString(
                            if (isCameraEnabled) R.string.meeting_str_camera_opened
                            else R.string.meeting_str_camera_closed
                        )
                    )
                }
            }
            // 状态更新由 observeRepositoryState 处理
        }
    }

    /**
     * 切换前后摄像头。
     */
    fun switchCamera() {
        viewModelScope.launch(ioDispatcher) {
            logInfo("执行切换前后摄像头操作")
            repository.switchCamera()
        }
    }

    /**
     * 申请开启麦克风（通常在被禁麦时使用）。
     */
    fun applyOpenMic() {
        viewModelScope.launch {
            logInfo("执行申请开启麦克风操作")
            val result = withContext(ioDispatcher) { repository.applyOpenMic() }
            result.fold(
                onSuccess = {
                    logInfo("申请开启麦克风成功")
                    sendEvent(McuMeetEvent.ShowToast(appContext.getString(R.string.meeting_audio_open_hint_toast)))
                },
                onError = { error, _ -> handleError(error, "申请开启麦克风失败") }
            )
        }
    }

    /**
     * 启动本地摄像头预览。
     * @return Result<Unit> 表示操作结果。
     */
    fun startPreview(): Result<Unit> {
        logInfo("执行启动预览操作")
        // 这个操作通常是同步或快速完成的，可以在调用线程执行，但 repository 实现可能不同
        // 假设 repository.startPreview 是同步或快速的
        return repository.startPreview(QStreamType.MAIN).fold(
            onSuccess = {
                logInfo("启动预览成功")
                Result.Success(Unit)
            },
            onError = { exception, errorCode ->
                logError("启动预览失败", exception)
                Result.Error(exception, errorCode)
            }
        )
        // 状态更新由 observeRepositoryState 处理
    }

    // ### 屏幕共享 ###

    /**
     * 切换屏幕共享状态（开始或停止）。
     * 会进行权限、角色、会议状态和当前共享状态的检查。
     */
    fun toggleSharing() {
        viewModelScope.launch {
            val prerequisites = checkSharingPrerequisites() // IO 线程检查前提条件
            if (prerequisites != SharingPrerequisiteResult.CAN_PROCEED) {
                handleSharingPrerequisiteFailure(prerequisites) // 主线程处理失败提示
                return@launch
            }

            // 执行切换操作
            if (viewState.value.isSelfSharing) {
                // --- 停止共享 ---
                logInfo("执行停止屏幕共享操作")
                val result = withContext(ioDispatcher) { repository.stopScreenSharing() }
                result.fold(
                    onSuccess = {
                        logInfo("停止屏幕共享成功")
                        // 状态更新由 observeRepositoryState 处理
                        sendEvent(McuMeetEvent.ShowToast("已停止屏幕共享"))
                    },
                    onError = { error, _ -> handleError(error, "停止屏幕共享失败") }
                )
            } else {
                // --- 准备开始共享 ---
                logInfo("准备开始屏幕共享，显示共享选项对话框")
                // 显示共享选项对话框（如选择共享屏幕、白板等）
                sendEvent(McuMeetEvent.ShowDialog(McuMeetEvent.DIALOG_TYPE_SHARE))
            }
        }
    }

    /** 屏幕共享前提条件检查结果枚举 */
    private enum class SharingPrerequisiteResult {
        CAN_PROCEED, NO_PERMISSION, IS_AUDIENCE, WHITEBOARD_ACTIVE, VOICE_MODE_ACTIVE, OTHER_SHARING
    }

    /**
     * 检查切换屏幕共享的前提条件。
     * @return SharingPrerequisiteResult 枚举值。
     */
    private suspend fun checkSharingPrerequisites(): SharingPrerequisiteResult =
        withContext(ioDispatcher) {
            val state = viewState.value
            val selfInfo = repository.getSelfInfo().successDataOrNull()
            val roomInfo = repository.getMeetingInfo()

            if (selfInfo == null) {
                logError("共享检查：无法获取个人信息")
                return@withContext SharingPrerequisiteResult.NO_PERMISSION // 或其他错误类型
            }

            // 观众不能共享
            if (selfInfo.role == QRoomRole.AUDIENCE.ordinal) {
                logInfo("共享检查：观众角色")
                return@withContext SharingPrerequisiteResult.IS_AUDIENCE
            }
            // 语音模式下不能共享
            if (state.isVoiceMode) {
                logInfo("共享检查：语音模式激活")
                return@withContext SharingPrerequisiteResult.VOICE_MODE_ACTIVE
            }
            // 如果会议当前是白板共享，不能发起屏幕共享
            if (roomInfo.shareType == EnMeetShareType.MEET_SHARE_TYPE_WHITEBOADR && !state.isSelfSharing) {
                logInfo("共享检查：白板共享激活")
                return@withContext SharingPrerequisiteResult.WHITEBOARD_ACTIVE
            }
            // 如果已有他人在共享，不能发起新的共享
            if (state.isSharing && !state.isSelfSharing) {
                logInfo("共享检查：已有他人在共享")
                return@withContext SharingPrerequisiteResult.OTHER_SHARING
            }

            // TODO: 添加悬浮窗权限检查？ checkScreenCapturePermission()
            // if (!checkScreenCapturePermission()) {
            //     logInfo("共享检查：缺少悬浮窗权限")
            //     return@withContext SharingPrerequisiteResult.NO_PERMISSION
            // }

            logInfo("共享检查：条件满足")
            return@withContext SharingPrerequisiteResult.CAN_PROCEED
        }

    /** 处理共享前提条件检查失败的情况，发送相应提示 */
    private fun handleSharingPrerequisiteFailure(result: SharingPrerequisiteResult) {
        when (result) {
            SharingPrerequisiteResult.IS_AUDIENCE -> sendEvent(
                McuMeetEvent.ShowToast(
                    appContext.getString(
                        com.huidiandian.meeting.base.R.string.base_str_please_hand_up_to_speak
                    )
                )
            )

            SharingPrerequisiteResult.WHITEBOARD_ACTIVE -> sendEvent(
                McuMeetEvent.ShowToast(
                    appContext.getString(R.string.msg_whiteboard_already_being_shared)
                )
            )

            SharingPrerequisiteResult.VOICE_MODE_ACTIVE -> sendEvent(
                McuMeetEvent.ShowToast(
                    appContext.getString(R.string.msg_voice_mode_sharing_unavailable)
                )
            )

            SharingPrerequisiteResult.OTHER_SHARING -> sendEvent(
                McuMeetEvent.ShowToast(
                    appContext.getString(
                        R.string.msg_someone_already_sharing
                    )
                )
            )

            SharingPrerequisiteResult.NO_PERMISSION -> sendEvent(McuMeetEvent.ShowToast("缺少必要权限或信息")) // 可细化
            else -> {} // CAN_PROCEED 不处理
        }
    }


    /**
     * （由 UI 层触发）请求系统进行屏幕捕捉。
     * 结果由 Activity 的 onActivityResult 处理，然后可能调用 startSharing。
     */
    fun startScreenShare() {
        // 实际的捕捉请求由 Repository 实现，可能需要 Activity Context
        // ViewModel 只负责转发意图
        viewModelScope.launch(ioDispatcher) {
            logInfo("请求屏幕捕捉权限")
            repository.captureScreen() // 这个方法可能只是发送一个事件给 Activity
        }
    }

    fun startSharing(): Deferred<Result<Unit>> {
        val deferred = CompletableDeferred<Result<Unit>>()
        viewModelScope.launch(ioDispatcher) {
            val result = repository.startScreenSharing().fold(
                onSuccess = {
                    _viewState.updateSafely { it.copy(isSharing = true, isSelfSharing = true) }
                    sendEvent(McuMeetEvent.ShowToast("开始共享"))
                    Result.Success(Unit)
                },
                onError = { error, _ ->
                    handleError(error, "开始屏幕共享失败")
                    Result.Error(error)
                }
            )
            deferred.complete(result)
        }
        return deferred
    }

    /**
     * （在获取悬浮窗权限后由 UI 层调用）继续执行屏幕共享流程。
     * 这通常发生在请求悬浮窗权限后返回 Activity 时。
     */
    fun continueScreenSharing() {
        // 重新尝试开始共享推流的逻辑可能与 startSharingAfterCapture 类似
        // 或者只是简单地调用 repository 的某个方法来继续
        viewModelScope.launch(ioDispatcher) {
            logInfo("获取悬浮窗权限后，继续屏幕共享")
            // 假设 repository.startScreenSharing() 内部能处理这种情况
            // 或者需要一个 repository.continueScreenSharing() 方法
            repository.startScreenSharing() // 重新尝试启动共享
                .onError { error, _ -> handleError(error, "继续屏幕共享失败") }
        }
    }

    /**
     * 在屏幕共享启动失败后，重置共享状态。
     */
    fun resetSharingStateAfterFailure() {
        viewModelScope.launch(ioDispatcher) {
            logInfo("重置屏幕共享失败后的状态")
            repository.stopScreenSharing() // 尝试调用停止接口来清理状态
        }
    }


    // ### 参与者管理与流处理 ###

    /**
     * 刷新参会者列表。
     */
    fun refreshParticipants() {
        viewModelScope.launch(ioDispatcher) {
            logInfo("执行刷新参会者列表操作")
            repository.refreshParticipants()
        }
    }

    /**
     * 初始化 RTC 推拉流设置。
     */
    fun setupStream() {
        viewModelScope.launch(ioDispatcher) {
            repository.setupStream()
        }
    }

    /**
     * 设置 RTC 引擎统计信息的回调间隔。
     * @param interval 回调间隔时间（秒），0 表示关闭。
     */
    fun setStreamStatsInterval(interval: Int) {
        viewModelScope.launch(ioDispatcher) {
            logInfo("设置流统计信息回调间隔: $interval 秒")
            repository.setStreamStatsInterval(interval)
        }
    }

    // ### 语音模式 ###

    /**
     * 切换语音模式。
     * @param enabled true 进入语音模式，false 退出语音模式。
     */
    fun switchVoiceMode(enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            logInfo("执行切换语音模式操作: $enabled")
            repository.switchVoiceMode(enabled)
            // 状态更新和摄像头处理逻辑已移至 Repository 或通过 state 流自动处理
        }
    }

    // ### 错误处理与清理 ###

    /**
     * 通用错误处理方法。
     * @param error 异常对象。
     * @param message 附加的错误描述信息。
     */
    private fun handleError(error: Throwable, message: String) {
        logError(message, error)
        // 可以在这里根据 error 类型发送更具体的事件给 UI
        // 例如： if (error is NetworkException) sendEvent(...)
        // 保持现有的通用错误处理
        ErrorHandler.handle(error, tag, message)
        // 可以考虑发送一个通用的错误事件给 UI
        // sendEvent(McuMeetEvent.ShowToast("操作失败: ${error.message ?: message}"))
    }

    /**
     * ViewModel 清理时调用，释放 Repository 资源。
     */
    override fun onCleared() {
        logInfo("ViewModel销毁 (onCleared)，释放资源")
        // 使用 SupervisorJob + launch 确保即使一个协程失败，其他也能继续
        // 但这里 repository.release() 应该设计为幂等且安全的
        repository.release()
        super.onCleared()
        logInfo("ViewModel已清理")
    }
}