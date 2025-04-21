package com.huidiandian.meeting.mcu.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bifrost.rtcengine.QRtcEngine
import com.bifrost.rtcengine.constants.QErrorCode
import com.bifrost.rtcengine.constants.QStreamType
import com.bifrost.rtcengine.entity.QStreamParams
import com.huidiandian.meeting.base.constant.LiveDataBusConstantBaseModule.ON_FIRST_REMOTE_VIDEO_FRAME_DECODED
import com.huidiandian.meeting.base.eventmsg.room.webrtc.onFirstRemoteVideoFrameDecodedMessage
import com.huidiandian.meeting.base.util.AppLogger
import com.jeremyliao.liveeventbus.LiveEventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.math.pow

/**
 * 屏幕共享视图模型，负责处理屏幕共享的业务逻辑
 */
class ScreenShareViewModel : ViewModel() {

    companion object {
        private const val TAG = "ScreenShareViewModel"
        private const val SUBSCRIBE_TIMEOUT = 10000L    // 订阅超时时间，单位毫秒
        private const val INITIAL_RETRY_DELAY = 1000L   // 初始重试延迟，单位毫秒
        private const val MAX_RETRY_DELAY = 15000L      // 最大重试延迟，单位毫秒
        private const val STEADY_RETRY_DELAY = 10000L   // 稳定重试延迟，单位毫秒
        private const val EXPONENTIAL_RETRY_COUNT = 5   // 指数退避重试次数
        private const val MAX_TOTAL_RETRY_TIME = 5 * 60 * 1000L // 最大总重试时间5分钟
    }

    private var account = ""
    private var streamType = QStreamType.SHARE
    private var subscribeJob: Job? = null
    private var retryCount = 0
    private var retryStartTime = 0L

    // 用于通知首帧接收状态
    private val _firstFrame = MutableLiveData<Boolean>()
    val firstFrame: LiveData<Boolean> = _firstFrame

    // 订阅状态
    private val _subscribeState = MutableLiveData<SubscribeState>()
    val subscribeState: LiveData<SubscribeState> = _subscribeState

    // 订阅状态枚举
    enum class SubscribeState {
        IDLE,           // 空闲状态
        SUBSCRIBING,    // 订阅中
        SUBSCRIBED,     // 已订阅
        FAILED,         // 订阅失败
    }

    private val firstRemoteVideoFrameDecodedObserver =
        Observer<onFirstRemoteVideoFrameDecodedMessage> { message ->
            AppLogger.getInstance().d("$TAG 处理首帧事件: $message")
            try {
                // 接收到与当前账号和流类型匹配的首帧
                if (message.account == account && message.streamType == streamType) {
                    AppLogger.getInstance().d(
                        "$TAG 接收到首帧: ${message.account}, ${message.streamType}"
                    )
                    _firstFrame.postValue(true)
                    _subscribeState.postValue(SubscribeState.SUBSCRIBED)
                    // 取消订阅任务，因为已经成功接收首帧
                    subscribeJob?.cancel()
                }
            } catch (e: Exception) {
                AppLogger.getInstance().e("$TAG 处理首帧事件时发生错误: ${e.message}")
            }
        }

    init {
        setupEventHandlers()
    }

    /**
     * 获取当前账号
     */
    fun getAccount(): String = account

    /**
     * 获取当前流类型
     */
    fun getStreamType(): QStreamType = streamType

    /**
     * 设置共享流
     * @param account 共享账号
     * @param streamType 共享类型
     */
    fun setupShareStream(account: String, streamType: Int?) {
        this.account = account
        this.streamType = streamType?.let { QStreamType.fromOrdinal(it) } ?: QStreamType.SHARE

        subscribeStreamWithRetry()
    }

    /**
     * 带退避重试机制的订阅流
     */
    private fun subscribeStreamWithRetry() {
        retryCount = 0
        retryStartTime = System.currentTimeMillis()
        _subscribeState.postValue(SubscribeState.IDLE)
        doSubscribeStream()
    }

    /**
     * 执行订阅流操作
     */
    private fun doSubscribeStream() {
        // 检查是否超过最大重试时间
        if (System.currentTimeMillis() - retryStartTime > MAX_TOTAL_RETRY_TIME && retryCount > 0) {
            AppLogger.getInstance().w("$TAG 超过最大重试时间，停止重试")
            _subscribeState.postValue(SubscribeState.FAILED)
            return
        }

        // 取消之前的订阅任务
        subscribeJob?.cancel()
        _subscribeState.postValue(SubscribeState.SUBSCRIBING)

        subscribeJob = viewModelScope.launch {
            try {
                AppLogger.getInstance().d("$TAG 开始订阅流，第 ${retryCount + 1} 次尝试")

                val result = withTimeoutOrNull(SUBSCRIBE_TIMEOUT) {
                    subscribeStreamInternal()
                } ?: QErrorCode.FAILED.ordinal

                if (result != QErrorCode.SUCCESS.ordinal) {
                    // 计算退避延迟
                    val delayTime = calculateBackoffDelay(retryCount)
                    AppLogger.getInstance()
                        .d("$TAG 订阅流失败，${delayTime}ms后重试，当前重试次数：$retryCount")

                    retryCount++
                    delay(delayTime)
                    doSubscribeStream()
                } else {
                    AppLogger.getInstance().d("$TAG 订阅流成功")
                    _subscribeState.postValue(SubscribeState.SUBSCRIBED)
                }
            } catch (e: CancellationException) {
                AppLogger.getInstance().d("$TAG 订阅流操作已取消")
            } catch (e: Exception) {
                AppLogger.getInstance().e("$TAG 订阅流操作发生错误: ${e.message}")

                // 计算退避延迟
                val delayTime = calculateBackoffDelay(retryCount)
                AppLogger.getInstance()
                    .d("$TAG 订阅流错误，${delayTime}ms后重试，当前重试次数：$retryCount")

                retryCount++
                delay(delayTime)
                doSubscribeStream()
            }
        }
    }

    /**
     * 计算退避延迟
     * 前5次使用指数退避，之后使用固定间隔
     * @param retryCount 重试次数
     * @return 延迟时间（毫秒）
     */
    private fun calculateBackoffDelay(retryCount: Int): Long {
        return if (retryCount < EXPONENTIAL_RETRY_COUNT) {
            // 指数退避策略：delay = initialDelay * (2^retryCount)，并限制最大值
            val delay = INITIAL_RETRY_DELAY * (2.0.pow(retryCount.toDouble())).toLong()
            min(delay, MAX_RETRY_DELAY)
        } else {
            // 固定间隔重试
            STEADY_RETRY_DELAY
        }
    }

    /**
     * 内部订阅流实现
     * @return 订阅结果码
     */
    private fun subscribeStreamInternal(): Int {
        val engine = QRtcEngine.get()
        val pullChannelId = engine.pullChannelId
        val roomId = engine.meetingInfo.room.id

        if (pullChannelId.isNullOrEmpty() || roomId.isNullOrEmpty()) {
            AppLogger.getInstance().e("$TAG 设置共享流失败: 通道ID或房间ID为空")
            return QErrorCode.FAILED.ordinal
        }

        // 检查是否已经拉取该流
        val isPullStream = engine.checkVideoPull(
            pullChannelId, roomId, account, streamType
        )

        if (isPullStream) {
            AppLogger.getInstance().d("$TAG 已订阅该流: $account, $streamType")
            // 如果已订阅，直接触发首帧事件
            _firstFrame.postValue(true)
            _subscribeState.postValue(SubscribeState.SUBSCRIBED)
            return QErrorCode.SUCCESS.ordinal
        }

        // 订阅视频流
        val result = engine.subscribeStream(pullChannelId, roomId, QStreamParams().apply {
            this.account = this@ScreenShareViewModel.account
            this.streamType = this@ScreenShareViewModel.streamType
            this.video = true
            this.audio = false
        })

        AppLogger.getInstance()
            .d("$TAG 订阅屏幕共享流结果: $result, 账号: $account, 类型: $streamType")
        return result
    }

    /**
     * 设置事件处理器
     */
    private fun setupEventHandlers() {
        // 监听远程视频首帧解码事件
        LiveEventBus.get(
            ON_FIRST_REMOTE_VIDEO_FRAME_DECODED,
            onFirstRemoteVideoFrameDecodedMessage::class.java
        ).observeForever(firstRemoteVideoFrameDecodedObserver)
        AppLogger.getInstance().d("$TAG 初始化事件监听器")
    }

    /**
     * 移除事件处理器
     */
    private fun removeEventHandlers() {
        LiveEventBus.get(
            ON_FIRST_REMOTE_VIDEO_FRAME_DECODED,
            onFirstRemoteVideoFrameDecodedMessage::class.java
        ).removeObserver(firstRemoteVideoFrameDecodedObserver)
        AppLogger.getInstance().d("$TAG 移除事件监听器")
    }

    /**
     * 取消订阅流
     */
    private fun unsubscribeStream() {
        // 取消当前的订阅任务
        subscribeJob?.cancel()
        subscribeJob = null
        _subscribeState.postValue(SubscribeState.IDLE)

        viewModelScope.launch {
            try {
                val engine = QRtcEngine.get()
                val pullChannelId = engine.pullChannelId
                val roomId = engine.meetingInfo.room.id

                if (pullChannelId.isNullOrEmpty() || roomId.isNullOrEmpty() || account.isEmpty()) {
                    AppLogger.getInstance().w("$TAG 取消订阅流失败: 参数不完整")
                    return@launch
                }

                // 取消订阅视频流
                engine.unsubscribeStream(
                    pullChannelId, account, roomId, streamType, true, false, true
                )
                AppLogger.getInstance().d("$TAG 已取消订阅流: $account, $streamType")
            } catch (e: Exception) {
                AppLogger.getInstance().e("$TAG 取消订阅流时发生错误: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        removeEventHandlers()
        unsubscribeStream()
        super.onCleared()
    }
}