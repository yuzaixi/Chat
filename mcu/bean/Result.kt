package com.huidiandian.meeting.mcu.bean

import android.content.Context
import com.bifrost.rtcengine.constants.QRoomRole
import com.bifrost.rtcengine.entity.QParticipant
import com.bifrost.rtcengine.entity.QRoom
import com.huidiandian.meeting.base.constant.MMKVConstant
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.base.util.MMKVUtil
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 统一的结果包装类
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable, val errorCode: Int? = null) : Result<Nothing>()

    inline fun <R> fold(
        onSuccess: (T) -> R,
        onError: (Throwable, Int?) -> R,
    ): R = when (this) {
        is Success -> onSuccess(data)
        is Error -> onError(exception, errorCode)
    }

    fun successDataOrNull(): T? {
        return when (this) {
            is Success -> data
            is Error -> null
        }
    }

    fun errorCodeOrNull(): Int? {
        return when (this) {
            is Success -> null
            is Error -> errorCode
        }
    }

    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (Throwable, Int?) -> Unit): Result<T> {
        if (this is Error) action(exception, errorCode)
        return this
    }

    // 为保持兼容性，保留原来的onError方法
    inline fun onError(action: (Throwable) -> Unit): Result<T> {
        if (this is Error) action(exception)
        return this
    }
}

/**
 * 统一的错误处理工具类
 */
object ErrorHandler {
    fun handle(error: Throwable, tag: String, message: String? = null) {
        val logMessage = message ?: "发生错误"
        AppLogger.getInstance().e("$tag: $logMessage - ${error.message}")
    }
}

/**
 * 所有状态类的基础接口
 */
interface BaseState

/**
 * MCU会议UI状态
 */
data class McuMeetState(
    val roomId: String = "",
    val isMicEnabled: Boolean = MMKVUtil.decodeBoolean(MMKVConstant.AUDIO_STATUS, false),
    val isHost: Boolean = false,
    val isCameraEnabled: Boolean = MMKVUtil.decodeBoolean(MMKVConstant.VIDEO_STATUS, false),
    val isSharing: Boolean = false,
    val isSelfSharing: Boolean = false,
    val isLecture: Boolean = false,
    val isHand: Boolean = false,
    val isLoading: Boolean = false,
    val isVoiceMode: Boolean = false,
    val isConnected: Boolean = false,
    val publishStreamState: PublishStreamState = PublishStreamState.IDLE,
    val pullStreamState: PullStreamState = PullStreamState.IDLE,
    val participants: List<ParticipantData> = emptyList(),
    val permissionState: PermissionState = PermissionState(),
    val error: Throwable? = null,
    val isFullscreen: Boolean = false,
    val fullscreenUserId: String? = null,
    val shareAccount: String = "",
    val meetingInfo: QRoom? = null,
    val roomCloseReason: Int = -1, // 房间关闭原因
    val role: QRoomRole? = null,
    val userInfo: QParticipant? = null,
    val recordingState: RecordState = RecordState(),
    val isPreview: Boolean = false,
    val memberNumbers: Int = 0,
) : BaseState

/**
 * 权限状态数据类
 */
data class PermissionState(
    val hasCamera: Boolean = false,
    val hasMicrophone: Boolean = false,
    val hasScreenCapture: Boolean = false,
)

data class RecordState(
    val isRecording: Boolean = false,
    val reason: Int = 0,
    val startTime: Long = 0L,
)

/**
 * 一次性事件密封类
 */
sealed class McuMeetEvent {
    data class Error(val exception: Throwable, val message: String) : McuMeetEvent()
    data class PermissionRequired(val permission: String) : McuMeetEvent()
    data class ShowToast(val message: String) : McuMeetEvent()
    data class ShowToastByErrorCode(val errorCode: Int) : McuMeetEvent()
    data class ShowDialog(val type: Int) : McuMeetEvent()
    data class ShowLeaveOrCloseDialog(val isHost: Boolean) : McuMeetEvent()
    object FinishRoom : McuMeetEvent()
    object FinishRoomAndJumpDuration : McuMeetEvent()

    companion object {
        /**
         * dialog类型
         */
        const val DIALOG_TYPE_SHARE = 1   //开启屏幕共享
        const val DIALOG_TYPE_MIC_BAN = 2 //申请开启麦克风
    }
}


/**
 * 线程安全的状态更新
 */
inline fun <T> MutableStateFlow<T>.updateSafely(crossinline action: (T) -> T) {
    val oldValue = value
    val newValue = action(oldValue)
    if (oldValue !== newValue) {
        value = newValue
    }
}

/**
 * 资源释放工具
 */
object ResourceCleaner {
    private const val TAG = "ResourceCleaner"

    fun releaseScreenSharing() {
        try {
            // 释放屏幕共享资源
            AppLogger.getInstance().d("$TAG: 释放屏幕共享资源")
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG: 释放屏幕共享资源失败 , ${e.message}")
        }
    }

    fun releaseMediaEngine() {
        try {
            // 释放媒体引擎资源
            AppLogger.getInstance().d("$TAG: 释放媒体引擎资源")
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG: 释放媒体引擎资源失败 , ${e.message}")
        }
    }

    fun releaseAllResources(context: Context) {
        AppLogger.getInstance().d("$TAG: 开始释放所有资源")
        releaseScreenSharing()
        releaseMediaEngine()

        // 清理其他资源
        AppLogger.getInstance().d("$TAG: 触发GC")
        System.gc()
    }
}