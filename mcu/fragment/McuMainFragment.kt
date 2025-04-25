package com.huidiandian.meeting.mcu.fragment

import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bifrost.rtcengine.QRtcEngine
import com.bifrost.rtcengine.entity.QCanvas
import com.bifrost.rtcengine.entity.QParticipant
import com.huidiandian.meeting.base.client.arguments.JNIEnum
import com.huidiandian.meeting.base.constant.LiveDataBusConstantBaseModule
import com.huidiandian.meeting.base.constant.MMKVConstant
import com.huidiandian.meeting.base.eventmsg.room.webrtc.OnAudioInStatus
import com.huidiandian.meeting.base.eventmsg.room.webrtc.OnLeftNotifyMsg
import com.huidiandian.meeting.base.eventmsg.room.webrtc.OnMicrophoneSoundLevel
import com.huidiandian.meeting.base.eventmsg.room.webrtc.OnVideoStatus
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.base.util.CircleTextImageViewUtil
import com.huidiandian.meeting.base.util.MMKVUtil
import com.huidiandian.meeting.base.vvm.MVVMFragment
import com.huidiandian.meeting.mcu.view.McuLayoutView
import com.huidiandian.meeting.mcu.viewmodel.McuMainViewModel
import com.huidiandian.meeting.meeting.R
import com.huidiandian.meeting.meeting.databinding.FragmentMcuMainBinding
import com.jeremyliao.liveeventbus.LiveEventBus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.math.abs

class McuMainFragment : MVVMFragment<FragmentMcuMainBinding, McuMainViewModel>() {

    override val viewModel: McuMainViewModel by viewModel()
    private var observerJobs = mutableListOf<Job>()
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastFullscreenTime = 0L
    private var mcuFirstFrameObserverJob: Job? = null
    private var firstFrameReceived = false

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.getInstance().e("$TAG 协程异常: ${throwable.stackTraceToString()}")
        if (isFragmentActive()) {
            setupMcuFirstFrameObserver()
        }
    }

    override fun injectBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentMcuMainBinding.inflate(inflater, container, false)

    override fun injectView() {
        setupSurfaceViewAspectRatio()
        setupMcuFirstFrameObserver()
    }

    override fun observeViewModel() {
        binding.let { viewModel.renderLocalPreview(it.mcuSurfaceView) }
        setupOtherObservers()
    }

    private fun setupSurfaceViewAspectRatio() {
        val rootView = binding.root
        rootView.post { adjustSurfaceViewSize() }
        val layoutChangeFlow = callbackFlow<Pair<Int, Int>> {
            val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                trySend(Pair(right - left, bottom - top))
            }
            rootView.addOnLayoutChangeListener(listener)
            awaitClose { rootView.removeOnLayoutChangeListener(listener) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            layoutChangeFlow
                .debounce(DEBOUNCE_TIME)
                .collect { (w, h) ->
                    if (hasSignificantSizeChanged(w, h, lastWidth, lastHeight)) {
                        lastWidth = w
                        lastHeight = h
                        adjustSurfaceViewSize()
                    }
                }
        }
    }

    private fun setupMcuFirstFrameObserver() {
        mcuFirstFrameObserverJob?.cancel()
        AppLogger.getInstance().d("$TAG 设置MCU首帧事件观察者")
        mcuFirstFrameObserverJob =
            viewLifecycleOwner.lifecycleScope.launch(coroutineExceptionHandler) {
                launch { observeMcuFirstFrame() }
                launch { observeLayoutRects() }
                launch { observeSfuFirstFrame() }
            }
    }

    private suspend fun observeMcuFirstFrame() {
        viewModel.mcuFirstFrame
            .onStart { AppLogger.getInstance().d("$TAG MCU首帧观察者已启动") }
            .flowOn(Dispatchers.Main.immediate)
            .retry(3) {
                AppLogger.getInstance().w("$TAG MCU首帧事件收集失败，准备重试: ${it.message}")
                delay(RETRY_DELAY_MS); true
            }
            .catch { e ->
                AppLogger.getInstance().e("$TAG MCU首帧事件收集异常: ${e.message}")
                if (!firstFrameReceived && isFragmentActive()) {
                    switchToMcuRenderingWithTimeoutDelayed()
                }
            }
            .collect { streamInfo ->
                AppLogger.getInstance().d("$TAG ★★★ 收到MCU首帧事件，切换到MCU渲染: $streamInfo")
                firstFrameReceived = true
                if (isFragmentActive()) {
                    switchToMcuRenderingWithTimeout()
                }
            }
    }

    private suspend fun observeLayoutRects() {
        viewModel.layoutRects
            .onStart { AppLogger.getInstance().d("$TAG 布局更新观察者已启动") }
            .flowOn(Dispatchers.Main.immediate)
            .retry(3) {
                AppLogger.getInstance().w("$TAG 布局更新事件收集失败，准备重试: ${it.message}")
                delay(RETRY_DELAY_MS); true
            }
            .catch { e -> AppLogger.getInstance().e("$TAG 布局更新事件收集异常: ${e.message}") }
            .collect { layoutRects ->
                if (isFragmentActive()) {
                    AppLogger.getInstance().d("$TAG 收到布局更新事件: $layoutRects")
                    binding.mcuLayoutView.setRects(layoutRects)
                }
            }
    }

    private suspend fun observeSfuFirstFrame() {
        viewModel.sfuFirstFrame
            .onStart { AppLogger.getInstance().d("$TAG SFU首帧观察者已启动") }
            .flowOn(Dispatchers.Main.immediate)
            .retry(3) {
                AppLogger.getInstance().w("$TAG SFU首帧事件收集失败，准备重试: ${it.message}")
                delay(RETRY_DELAY_MS); true
            }
            .catch { e -> AppLogger.getInstance().e("$TAG SFU首帧事件收集异常: ${e.message}") }
            .collect { streamInfo ->
                AppLogger.getInstance().d("$TAG ★★★ 收到SFU首帧事件，判断切换渲染全屏: $streamInfo")
                if (viewModel.currentRenderUserId.value != streamInfo.account) {
                    AppLogger.getInstance().d("$TAG 当前渲染用户ID不匹配，不做处理")
                    return@collect
                }
                if (!isFragmentActive()) return@collect
                withContext(Dispatchers.Main) {
                    binding.let {
                        QRtcEngine.get().addCanvas(
                            streamInfo.account,
                            QRtcEngine.get().meetingInfo.room.id,
                            streamInfo.streamType,
                            QCanvas(it.mcuSurfaceView)
                        )
                        it.groupInfo.visibility = ViewGroup.GONE
                        it.tvHostTag.visibility = ViewGroup.GONE
                    }
                }
            }
    }

    private fun switchToMcuRenderingWithTimeoutDelayed() {
        viewLifecycleOwner.lifecycleScope.launch {
            delay(SWITCH_DELAY_MS)
            if (isFragmentActive()) {
                switchToMcuRenderingWithTimeout()
            }
        }
    }

    private suspend fun switchToMcuRenderingWithTimeout() {
        try {
            withTimeout(SWITCH_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    if (isFragmentActive()) {
                        binding.let { viewModel.switchToMcuRendering(it.mcuSurfaceView) }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG MCU渲染切换超时或失败: ${e.message}")
            viewLifecycleOwner.lifecycleScope.launch {
                delay(RETRY_DELAY_MS)
                if (isFragmentActive()) {
                    binding.let { viewModel.switchToMcuRendering(it.mcuSurfaceView) }
                }
            }
        }
    }

    private fun hasSignificantSizeChanged(
        newWidth: Int, newHeight: Int, oldWidth: Int, oldHeight: Int,
    ) = abs(newWidth - oldWidth) > MIN_SIZE_CHANGE_THRESHOLD || abs(newHeight - oldHeight) > MIN_SIZE_CHANGE_THRESHOLD

    private fun adjustSurfaceViewSize() {
        val parent = binding.root
        val surfaceView = binding.mcuSurfaceView
        if (parent.width <= 0 || parent.height <= 0) return
        val (targetWidth, targetHeight) = viewModel.calculateAspectRatio(parent.width, parent.height)
        try {
            ConstraintSet().apply {
                clone(parent)
                constrainWidth(surfaceView.id, targetWidth)
                constrainHeight(surfaceView.id, targetHeight)
                centerHorizontally(surfaceView.id, ConstraintSet.PARENT_ID)
                centerVertically(surfaceView.id, ConstraintSet.PARENT_ID)
                applyTo(parent)
            }
            AppLogger.getInstance().d("$TAG 调整SurfaceView尺寸")
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG 调整SurfaceView尺寸失败: ${e.message}")
        }
    }

    fun exitFullScreen() {
        if (!isFragmentActive()) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFullscreenTime < FULLSCREEN_DEBOUNCE_TIME) {
            AppLogger.getInstance().d("$TAG 忽略快速的退出全屏请求，防止误触发")
            return
        }
        AppLogger.getInstance().d("$TAG 通过Fragment退出全屏")
        binding.let { viewModel.exitFullScreen(it.mcuSurfaceView) }
    }

    override fun initData() = Unit

    private fun setupOtherObservers() {
        observerJobs.clear()
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                observerJobs.add(launch(coroutineExceptionHandler) {
                    viewModel.currentRenderUserId.collect { userId ->
                        AppLogger.getInstance().d("$TAG 当前渲染用户ID变更: $userId")
                        val currentTime = System.currentTimeMillis()
                        if (userId.isNullOrEmpty() && currentTime - lastFullscreenTime < FULLSCREEN_DEBOUNCE_TIME) {
                            AppLogger.getInstance().d("$TAG 忽略快速的全屏状态重置")
                            return@collect
                        }
                        updateUiForRenderingMode(userId)
                    }
                })
            }
        }
        registerLiveEventObservers()
    }

    private fun registerLiveEventObservers() {
        LiveEventBus.get(LiveDataBusConstantBaseModule.ON_VIDEO_STATUS, OnVideoStatus::class.java)
            .observe(this) { event -> handleVideoStatus(event) }
        LiveEventBus.get(
            LiveDataBusConstantBaseModule.ON_AUDIO_IN_STATUS,
            OnAudioInStatus::class.java
        ).observe(this) { event -> handleAudioStatus(event) }
        LiveEventBus.get(
            LiveDataBusConstantBaseModule.ON_MICROPHONE_SOUND_LEVEL,
            OnMicrophoneSoundLevel::class.java
        ).observe(this) { event -> handleMicLevel(event) }
        LiveEventBus.get(LiveDataBusConstantBaseModule.ON_LEFT_NOTIFY, OnLeftNotifyMsg::class.java)
            .observe(this) { event -> handleLeftNotify(event) }
    }

    private fun handleVideoStatus(event: OnVideoStatus) {
        val userId = viewModel.currentRenderUserId.value
        if (userId.isNullOrEmpty() || userId != event.accountTo) {
            AppLogger.getInstance().d("$TAG 当前没有全屏用户或ID不匹配，忽略视频状态变化事件")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            QRtcEngine.get().getParticipant(event.accountTo).info?.let { userInfo ->
                val meetingInfo = QRtcEngine.get().meetingInfo
                withContext(Dispatchers.Main) {
                    if (!isFragmentActive()) return@withContext
                    binding.let {
                        if (event.status && userInfo.userInfo.account == MMKVUtil.decodeString(MMKVConstant.LOGIN_ACCOUNT)) {
                            it.groupInfo.visibility = ViewGroup.GONE
                            it.tvHostTag.visibility = ViewGroup.GONE
                        } else {
                            it.groupInfo.visibility = ViewGroup.VISIBLE
                            it.tvName.text = userInfo.userInfo.name
                            it.tvHostTag.visibility =
                                if (event.accountTo == meetingInfo.hostInfo.account) ViewGroup.VISIBLE else ViewGroup.GONE
                            CircleTextImageViewUtil.setViewData(
                                activity,
                                it.ivPhoto,
                                userInfo.userInfo.icon,
                                userInfo.userInfo.name,
                                JNIEnum.EnAccountType.ACCOUNT_SOFT_TERMIANL,
                                true
                            )
                            setupMicStatus(userInfo)
                        }
                    }
                }
            }
        }
    }

    private fun handleAudioStatus(event: OnAudioInStatus) {
        val userId = viewModel.currentRenderUserId.value
        if (userId.isNullOrEmpty() || userId != event.accountTo) {
            AppLogger.getInstance().d("$TAG 当前没有全屏用户或ID不匹配，忽略音频状态变化事件")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            QRtcEngine.get().getParticipant(event.accountTo).info?.let {
                withContext(Dispatchers.Main) {
                    if (isFragmentActive()) setupMicStatus(it)
                }
            }
        }
    }

    private fun handleMicLevel(event: OnMicrophoneSoundLevel) {
        val userId = viewModel.currentRenderUserId.value
        val loginAccount = MMKVUtil.decodeString(MMKVConstant.LOGIN_ACCOUNT)
        if (userId.isNullOrEmpty() || userId != loginAccount) return
        binding.let {
            (it.ivMic.drawable as? LayerDrawable)?.findDrawableByLayerId(android.R.id.progress)
                ?.let { clipDrawable ->
                    try {
                        clipDrawable.level = calculateMicLevel(event.soundLevel)
                    } catch (_: Exception) {}
                }
        }
    }

    private fun handleLeftNotify(event: OnLeftNotifyMsg) {
        val userId = viewModel.currentRenderUserId.value
        if (userId.isNullOrEmpty() || userId != event.account) {
            AppLogger.getInstance().d("$TAG 当前没有全屏用户或ID不匹配，忽略离开通知事件")
            return
        }
        AppLogger.getInstance().d("$TAG 收到离开通知，退出全屏")
        exitFullScreen()
    }

    private fun calculateMicLevel(soundLevel: Int): Int {
        val baseHeight = 4000
        val dynamicRange = 10000 - baseHeight
        return if (soundLevel <= 0) {
            baseHeight
        } else {
            val pct = soundLevel.coerceIn(0, 100) / 100.0
            val dyn = (pct * pct * dynamicRange).toInt()
            (baseHeight + dyn).coerceIn(baseHeight, 10000)
        }
    }

    private fun updateUiForRenderingMode(userId: String?) {
        if (!isFragmentActive()) return
        val isFullScreen = !userId.isNullOrEmpty()
        binding.let { bind ->
            bind.mcuLayoutView.visibility = if (isFullScreen) ViewGroup.GONE else ViewGroup.VISIBLE
            if (isFullScreen) {
                bind.mcuLayoutView.animate().alpha(0f).setDuration(200).start()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    QRtcEngine.get().getParticipant(userId!!).info?.let { userInfo ->
                        val meetingInfo = QRtcEngine.get().meetingInfo
                        withContext(Dispatchers.Main) {
                            if (!isFragmentActive()) return@withContext
                            binding.let { it2 ->
                                if (userId == MMKVUtil.decodeString(MMKVConstant.LOGIN_ACCOUNT) && userInfo.cameraStatus) {
                                    it2.groupInfo.visibility = ViewGroup.GONE
                                    it2.tvHostTag.visibility = ViewGroup.GONE
                                } else {
                                    it2.groupInfo.visibility = ViewGroup.VISIBLE
                                    it2.tvHostTag.visibility =
                                        if (userId == meetingInfo.hostInfo.account) ViewGroup.VISIBLE else ViewGroup.GONE
                                }
                                it2.tvName.text = userInfo.userInfo.name
                                CircleTextImageViewUtil.setViewData(
                                    activity,
                                    it2.ivPhoto,
                                    userInfo.userInfo.icon,
                                    userInfo.userInfo.name,
                                    JNIEnum.EnAccountType.ACCOUNT_SOFT_TERMIANL,
                                    true
                                )
                                setupMicStatus(userInfo)
                            }
                        }
                    }
                }
            } else {
                bind.mcuLayoutView.alpha = 0f
                bind.mcuLayoutView.animate().alpha(1f).setDuration(200).start()
                bind.groupInfo.visibility = ViewGroup.GONE
                bind.tvHostTag.visibility = ViewGroup.GONE
            }
        }
    }

    private fun setupMicStatus(participant: QParticipant) {
        if (!isFragmentActive()) return
        val isSelf = participant.userInfo.account == MMKVUtil.decodeString(MMKVConstant.LOGIN_ACCOUNT)
        binding.ivMic.setImageResource(
            when {
                isSelf && participant.micStatus -> R.drawable.layer_icon_mic_opened
                !isSelf && participant.micStatus -> R.drawable.icon_mic_opened
                else -> R.drawable.icon_mic_closed
            }
        )
    }

    override fun onDestroyView() {
        mcuFirstFrameObserverJob?.cancel()
        mcuFirstFrameObserverJob = null
        observerJobs.forEach { it.cancel() }
        observerJobs.clear()
        try {
            binding.mcuSurfaceView.release()
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG 释放SurfaceView失败: ${e.message}")
        }
        super.onDestroyView()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.root.post { adjustSurfaceViewSize() }
    }

    fun getMcuLayoutView(): McuLayoutView? = view?.findViewById(R.id.mcuLayoutView)

    fun release() {
        if (!isFragmentActive()) return
        binding.let {
            QRtcEngine.get().removeCanvas(QCanvas(it.mcuSurfaceView))
            it.mcuSurfaceView.release()
        }
    }

    fun enterFullScreen(userId: String, streamType: Int) {
        if (!isFragmentActive()) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFullscreenTime < FULLSCREEN_DEBOUNCE_TIME) {
            AppLogger.getInstance().d("$TAG 忽略快速的进入全屏请求，防止误触发")
            return
        }
        AppLogger.getInstance().d("$TAG 通过Fragment进入全屏 userId=$userId streamType=$streamType")
        lastFullscreenTime = currentTime
        binding.let { viewModel.enterFullScreen(userId, streamType, it.mcuSurfaceView) }
    }

    fun isFullScreen(): Boolean = viewModel.isFullScreen()

    private fun isFragmentActive(): Boolean =
        isAdded && !isDetached && activity != null && view != null

    companion object {
        fun newInstance() = McuMainFragment()
        private const val TAG = "McuMainFragment"
        private const val RETRY_DELAY_MS = 300L
        const val DEBOUNCE_TIME = 150L
        const val SWITCH_DELAY_MS = 1500L
        const val MIN_SIZE_CHANGE_THRESHOLD = 4
        const val FULLSCREEN_DEBOUNCE_TIME = 100L
        const val SWITCH_TIMEOUT_MS = 5000L
    }
}