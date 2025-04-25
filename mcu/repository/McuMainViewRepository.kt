package com.huidiandian.meeting.mcu.repository

import androidx.lifecycle.Observer
import cn.geedow.netprotocol.basicDataStructure.JNIView
import com.bifrost.rtcengine.QRtcEngine
import com.bifrost.rtcengine.constants.QStreamType
import com.bifrost.rtcengine.entity.QCanvas
import com.bifrost.rtcengine.entity.QStreamParams
import com.bifrost.rtcengine.entity.QSurfaceView
import com.huidiandian.meeting.base.constant.LiveDataBusConstantBaseModule.ON_FIRST_REMOTE_VIDEO_FRAME_DECODED
import com.huidiandian.meeting.base.constant.LiveDataBusConstantBaseModule.ON_MEETING_LAYOUT_CHANGED
import com.huidiandian.meeting.base.constant.MMKVConstant
import com.huidiandian.meeting.base.eventmsg.room.webrtc.OnMeetingLayoutChanged
import com.huidiandian.meeting.base.eventmsg.room.webrtc.onFirstRemoteVideoFrameDecodedMessage
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.base.util.MMKVUtil
import com.huidiandian.meeting.mcu.bean.StreamInfo
import com.jeremyliao.liveeventbus.LiveEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class McuMainViewRepository : IMcuMainRepository {

    // 协程作用域
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 互斥锁防止首帧事件并发
    private val mcuFirstFrameMutex = Mutex()

    // 布局变更事件流
    private val _layoutChangedEvent = MutableStateFlow<List<JNIView>>(emptyList())
    override val layoutChangedEvent = _layoutChangedEvent.asStateFlow()

    // 首帧事件流
    private val _mcuFirstFrame = MutableSharedFlow<StreamInfo>(replay = 1, extraBufferCapacity = 64)
    override val mcuFirstFrame = _mcuFirstFrame.asSharedFlow()

    private val _sfuFirstFrame = MutableSharedFlow<StreamInfo>(replay = 1, extraBufferCapacity = 16)
    override val sfuFirstFrame = _sfuFirstFrame.asSharedFlow()

    // 事件注册原子标记
    private val eventHandlerRegistered = AtomicBoolean(false)

    // 首帧处理原子标记
    private val mcuFirstFrameProcessed = AtomicBoolean(false)

    private val jobList = mutableListOf<Job>()

    private companion object {
        const val TAG = "McuMainViewRepository"
    }

    // 布局变更事件监听
    private val onMeetingLayoutChangedNotify = Observer<OnMeetingLayoutChanged> { event ->
        if (event.layouts.isNotEmpty()) {
            AppLogger.getInstance().d("$TAG 收到布局变更: ${event.layouts.size} 个布局")
            _layoutChangedEvent.value = event.layouts.toList()
        }
    }

    private val onFirstRemoteVideoFrameDecodedNotify =
        Observer<onFirstRemoteVideoFrameDecodedMessage> { event ->
            repositoryScope.launch {
                if (event.streamType.ordinal == QStreamType.MCU.ordinal) {
                    AppLogger.getInstance().d("$TAG 收到MCU流首帧解码: ${event.account}")
                    try {
                        mcuFirstFrameMutex.withLock {
                            if (mcuFirstFrameProcessed.compareAndSet(false, true)) {
                                val streamInfo = StreamInfo(event.account, event.streamType)
                                AppLogger.getInstance().d("$TAG 发送MCU首帧事件: $streamInfo")
                                _mcuFirstFrame.emit(streamInfo)
                            } else {
                                AppLogger.getInstance().d("$TAG 首帧已处理, 忽略重复通知")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.getInstance().d("$TAG 发送MCU首帧事件异常: ${e.message}")
                        mcuFirstFrameProcessed.set(false)
                    }
                } else {
                    try {
                        AppLogger.getInstance()
                            .d("$TAG 收到首帧解码事件: ${event.account}, ${event.streamType}")
                        _sfuFirstFrame.emit(StreamInfo(event.account, event.streamType))
                    } catch (e: Exception) {
                        AppLogger.getInstance().d("$TAG 发送SFU首帧事件异常: ${e.message}")
                    }
                }
            }
        }

    init {
        setEventHandler()
    }

    override fun renderLocalPreview(surfaceView: QSurfaceView): Boolean {
        return try {
            QRtcEngine.get().removeCanvas(QCanvas(surfaceView))
            QRtcEngine.get().addPreview(QCanvas(surfaceView))
            val isMirror = MMKVUtil.decodeBoolean(MMKVConstant.MY_VIDEO_MIRROR)
            surfaceView.setMirror(isMirror)
            AppLogger.getInstance().d("$TAG 本地预览渲染成功")
            true
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG 本地预览渲染失败: ${e.message}")
            false
        }
    }

    override suspend fun switchToFullScreenRender(
        userId: String,
        streamType: Int,
        surfaceView: QSurfaceView,
    ): Boolean {
        if (userId.isEmpty()) {
            AppLogger.getInstance().e("$TAG 全屏渲染失败: 用户ID为空")
            return false
        }
        return try {
            val roomId = QRtcEngine.get().meetingInfo.room?.id
            if (roomId.isNullOrEmpty()) {
                AppLogger.getInstance().e("$TAG 全屏渲染失败: 房间ID为空")
                return false
            }
            val pullChannelId = QRtcEngine.get().pullChannelId
            if (pullChannelId.isNullOrEmpty()) {
                AppLogger.getInstance().e("$TAG 全屏渲染失败: 拉流频道ID为空")
                return false
            }
            QRtcEngine.get().removeCanvas(QCanvas(surfaceView))
            val isVideoPullSuccess = QRtcEngine.get().checkVideoPull(
                QRtcEngine.get().pullChannelId,
                roomId,
                userId,
                QStreamType.fromOrdinal(streamType)
            )
            AppLogger.getInstance().d("$TAG 拉流检查结果: $isVideoPullSuccess")
            if (isVideoPullSuccess) {
                QRtcEngine.get().addCanvas(
                    userId,
                    roomId,
                    QStreamType.fromOrdinal(streamType),
                    QCanvas(surfaceView)
                )
                surfaceView.setMirror(false)
                AppLogger.getInstance().d("$TAG 全屏渲染成功: $userId")
                true
            } else {
                AppLogger.getInstance().d("$TAG 拉取流")
                QRtcEngine.get().subscribeStream(pullChannelId, userId, QStreamParams().apply {
                    account = userId
                    this.streamType = QStreamType.fromOrdinal(streamType)
                    video = true
                    audio = false
                })
                true
            }
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG 全屏渲染失败: ${e.message}")
            false
        }
    }

    override suspend fun renderMcuScreen(surfaceView: QSurfaceView): Boolean {
        return try {
            val roomId = QRtcEngine.get().meetingInfo.room?.id
            if (roomId.isNullOrEmpty()) {
                AppLogger.getInstance().e("$TAG MCU渲染失败: 房间ID为空")
                return false
            }
            mcuFirstFrameProcessed.set(false)
            QRtcEngine.get().removeCanvas(QCanvas(surfaceView))
            QRtcEngine.get().addCanvas("", roomId, QStreamType.MCU, QCanvas(surfaceView))
            surfaceView.setMirror(false)
            AppLogger.getInstance().d("$TAG MCU渲染设置成功")
            true
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG MCU渲染失败: ${e.message}")
            false
        }
    }

    override fun release() {
        removeEventHandler()
        mcuFirstFrameProcessed.set(false)
        jobList.forEach { it.cancel() }
        jobList.clear()
        repositoryScope.launch {
            try {
                _mcuFirstFrame.resetReplayCache()
            } catch (e: Exception) {
                AppLogger.getInstance().d("$TAG 重置事件缓存异常: ${e.message}")
            }
        }
    }

    private fun setEventHandler() {
        if (eventHandlerRegistered.compareAndSet(false, true)) {
            try {
                LiveEventBus.get(ON_MEETING_LAYOUT_CHANGED, OnMeetingLayoutChanged::class.java)
                    .observeForever(onMeetingLayoutChangedNotify)
                LiveEventBus.get(
                    ON_FIRST_REMOTE_VIDEO_FRAME_DECODED,
                    onFirstRemoteVideoFrameDecodedMessage::class.java
                ).observeForever(onFirstRemoteVideoFrameDecodedNotify)
                AppLogger.getInstance().d("$TAG 事件监听器注册成功")
            } catch (e: Exception) {
                eventHandlerRegistered.set(false)
                AppLogger.getInstance().e("$TAG 设置事件监听失败: ${e.message}")
            }
        }
    }

    private fun removeEventHandler() {
        if (eventHandlerRegistered.compareAndSet(true, false)) {
            try {
                LiveEventBus.get(ON_MEETING_LAYOUT_CHANGED, OnMeetingLayoutChanged::class.java)
                    .removeObserver(onMeetingLayoutChangedNotify)
                LiveEventBus.get(
                    ON_FIRST_REMOTE_VIDEO_FRAME_DECODED,
                    onFirstRemoteVideoFrameDecodedMessage::class.java
                ).removeObserver(onFirstRemoteVideoFrameDecodedNotify)
                AppLogger.getInstance().d("$TAG 事件监听器移除成功")
            } catch (e: Exception) {
                AppLogger.getInstance().e("$TAG 移除事件监听失败: ${e.message}")
            }
        }
    }

    override fun exitFullScreenStream(account: String?) {
        if (account.isNullOrEmpty()) {
            AppLogger.getInstance().e("$TAG 退出全屏失败: 用户ID为空")
            return
        }
        val job = repositoryScope.launch(Dispatchers.IO) {
            val roomId = QRtcEngine.get().meetingInfo.room?.id
            QRtcEngine.get().unsubscribeStream(
                QRtcEngine.get().pullChannelId,
                roomId, account,
                QStreamType.MAIN, true, false, true
            )
        }
        jobList.add(job)
    }
}