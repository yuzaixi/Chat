package com.huidiandian.meeting.mcu.repository

import androidx.lifecycle.Observer
import cn.geedow.netprotocol.basicDataStructure.JNIView
import com.bifrost.rtcengine.QRtcEngine
import com.bifrost.rtcengine.constants.QStreamType
import com.bifrost.rtcengine.entity.QCanvas
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MCU会议数据仓库实现
 */
class McuMainViewRepository : IMcuMainRepository {

    // 创建协程作用域用于发送事件
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 添加互斥锁保护关键操作
    private val mcuFirstFrameMutex = Mutex()

    private val _layoutChangedEvent = MutableStateFlow<List<JNIView>>(emptyList())
    override val layoutChangedEvent = _layoutChangedEvent.asStateFlow()

    // 增加缓冲区大小确保事件不会被丢弃
    private val _mcuFirstFrame = MutableSharedFlow<StreamInfo>(
        replay = 1,
        extraBufferCapacity = 64
    )
    override val mcuFirstFrame = _mcuFirstFrame.asSharedFlow()

    // 事件监听注册标记，确保线程安全
    private val eventHandlerRegistered = AtomicBoolean(false)

    // 添加一个标记用于跟踪是否已经处理过MCU首帧
    private val mcuFirstFrameProcessed = AtomicBoolean(false)

    private companion object {
        const val TAG = "McuMainViewRepository"
    }

    private val onMeetingLayoutChangedNotify = Observer<OnMeetingLayoutChanged> { event ->
        if (event.layouts.isNotEmpty()) {
            AppLogger.getInstance().d("$TAG 收到布局变更通知: ${event.layouts.size} 个布局")
            _layoutChangedEvent.value = event.layouts.toList()
        }
    }

    private val onFirstRemoteVideoFrameDecodedNotify =
        Observer<onFirstRemoteVideoFrameDecodedMessage> { event ->
            // 当前fragment只关注MCU流的首帧解码通知
            if (event.streamType.ordinal == QStreamType.MCU.ordinal) {
                AppLogger.getInstance().d("$TAG 收到MCU流首帧解码通知: ${event.account}")

                // 使用协程发送事件，确保emit不会被挂起
                repositoryScope.launch {
                    try {
                        mcuFirstFrameMutex.withLock {
                            // 确保只处理一次首帧通知或在切换模式后可以重新处理
                            if (mcuFirstFrameProcessed.compareAndSet(false, true)) {
                                val streamInfo = StreamInfo(event.account, event.streamType)
                                AppLogger.getInstance().d("$TAG 尝试发送MCU首帧事件: $streamInfo")

                                // 使用emit替代tryEmit以确保发送成功
                                _mcuFirstFrame.emit(streamInfo)
                                AppLogger.getInstance().d("$TAG MCU首帧事件发送成功")
                            } else {
                                AppLogger.getInstance().d("$TAG 忽略重复的MCU首帧通知，首帧已处理")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.getInstance()
                            .d("$TAG 发送MCU首帧事件失败: ${e.printStackTrace()}")
                        // 如果发送失败，重置标记允许重试
                        mcuFirstFrameProcessed.set(false)
                    }
                }
            }
        }

    init {
        setEventHandler()
    }

    override fun renderLocalPreview(surfaceView: QSurfaceView): Boolean {
        return try {
            // 移除可能已存在的Canvas配置
            QRtcEngine.get().removeCanvas(QCanvas(surfaceView))
            // 渲染本地预览
            QRtcEngine.get().addPreview(QCanvas(surfaceView))
            val isMirror = MMKVUtil.decodeBoolean(MMKVConstant.MY_VIDEO_MIRROR)
            surfaceView.setMirror(true)
            AppLogger.getInstance().d("$TAG 本地预览渲染成功")
            true
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG 本地预览渲染失败: ${e.printStackTrace()}")
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
            QRtcEngine.get().removeCanvas(QCanvas(surfaceView))
            QRtcEngine.get().addCanvas(
                userId,
                roomId,
                QStreamType.fromOrdinal(streamType),
                QCanvas(surfaceView)
            )
            surfaceView.setMirror(false)
            AppLogger.getInstance().d("$TAG 全屏渲染成功: $userId")
            true
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG 全屏渲染失败: ${e.printStackTrace()}")
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

            // 重置首帧处理标记，允许再次接收首帧通知
            mcuFirstFrameProcessed.set(false)
            QRtcEngine.get().removeCanvas(QCanvas(surfaceView))
            QRtcEngine.get().addCanvas("", roomId, QStreamType.MCU, QCanvas(surfaceView))
            surfaceView.setMirror(false)
            AppLogger.getInstance().d("$TAG MCU渲染设置成功")
            true
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG MCU渲染失败: ${e.printStackTrace()}")
            false
        }
    }

    override fun release() {
        removeEventHandler()

        // 确保在释放资源时重置状态
        mcuFirstFrameProcessed.set(false)

        // 手动触发一次事件，确保观察者可以完成清理
        repositoryScope.launch {
            try {
                _mcuFirstFrame.resetReplayCache()
            } catch (e: Exception) {
                // 忽略异常
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
                AppLogger.getInstance().e("$TAG 设置事件监听失败: ${e.printStackTrace()}")
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
                AppLogger.getInstance().e("$TAG 移除事件监听失败: ${e.printStackTrace()}")
            }
        }
    }
}