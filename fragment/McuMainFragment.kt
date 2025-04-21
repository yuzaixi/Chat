package com.huidiandian.meeting.mcu.fragment

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bifrost.rtcengine.QRtcEngine
import com.bifrost.rtcengine.entity.QCanvas
import com.bifrost.rtcengine.entity.QSurfaceView
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.base.vvm.MVVMFragment
import com.huidiandian.meeting.mcu.view.McuLayoutView
import com.huidiandian.meeting.mcu.viewmodel.McuMainViewModel
import com.huidiandian.meeting.meeting.R
import com.huidiandian.meeting.meeting.databinding.FragmentMcuMainBinding
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    private var debounceJob: Job? = null
    private var lastFullscreenTime = 0L
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.getInstance().e("$TAG 协程异常: ${throwable.stackTraceToString()}")
        setupMcuFirstFrameObserver() // 关键流自动恢复
    }
    private var mcuFirstFrameObserverJob: Job? = null
    private var firstFrameReceived = false

    override fun injectBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): FragmentMcuMainBinding {
        return FragmentMcuMainBinding.inflate(inflater, container, false)
    }

    override fun injectView() {
        setupSurfaceViewAspectRatio()
        setupLayoutViewListeners()
        setupMcuFirstFrameObserver()
    }

    override fun observeViewModel() {
        viewModel.renderLocalPreview(binding.mcuSurfaceView)
        setupOtherObservers()
    }

    private fun setupSurfaceViewAspectRatio() {
        val rootView = binding.root
        rootView.post { adjustSurfaceViewSize() }
        rootView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val newWidth = right - left
            val newHeight = bottom - top
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            if (hasSignificantSizeChanged(newWidth, newHeight, oldWidth, oldHeight)) {
                debounceLayoutChange {
                    lastWidth = newWidth
                    lastHeight = newHeight
                    adjustSurfaceViewSize()
                }
            }
        }
    }

    private fun setupMcuFirstFrameObserver() {
        mcuFirstFrameObserverJob?.cancel()
        AppLogger.getInstance().d("$TAG 设置MCU首帧事件观察者")
        mcuFirstFrameObserverJob = viewModel.mcuFirstFrame.onStart {
            AppLogger.getInstance().d("$TAG MCU首帧观察者已启动")
        }.flowOn(Dispatchers.Main.immediate).retry(3) {
            AppLogger.getInstance().w("$TAG MCU首帧事件收集失败，准备重试: ${it.message}")
            delay(300); true
        }.catch { e ->
            AppLogger.getInstance().e("$TAG MCU首帧事件收集异常: ${e.message}")
            if (!firstFrameReceived) {
                AppLogger.getInstance().w("$TAG 未收到首帧事件，主动切换到MCU渲染")
                lifecycleScope.launch { delay(1500); switchToMcuRenderingWithTimeout() }
            }
        }.onEach { streamInfo ->
            AppLogger.getInstance().d("$TAG ★★★ 收到MCU首帧事件，切换到MCU渲染: $streamInfo")
            firstFrameReceived = true
            switchToMcuRenderingWithTimeout()
        }.launchIn(lifecycleScope)
        viewModel.layoutRects.onStart {
            AppLogger.getInstance().d("$TAG 布局更新观察者已启动")
        }.flowOn(Dispatchers.Main.immediate).retry(3) {
            AppLogger.getInstance().w("$TAG 布局更新事件收集失败，准备重试: ${it.message}")
            delay(300); true
        }.catch { e ->
            AppLogger.getInstance().e("$TAG 布局更新事件收集异常: ${e.message}")
        }.onEach { layoutRects ->
            AppLogger.getInstance().d("$TAG 收到布局更新事件: $layoutRects")
            binding.mcuLayoutView.setRects(layoutRects)
        }.launchIn(lifecycleScope)
    }

    private suspend fun switchToMcuRenderingWithTimeout() {
        try {
            withTimeout(5000L) {
                withContext(Dispatchers.Main) {
                    viewModel.switchToMcuRendering(binding.mcuSurfaceView)
                }
            }
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG MCU渲染切换超时或失败: ${e.message}")
            delay(2000)
            viewModel.switchToMcuRendering(binding.mcuSurfaceView)
        }
    }

    private fun hasSignificantSizeChanged(
        newWidth: Int,
        newHeight: Int,
        oldWidth: Int,
        oldHeight: Int,
    ): Boolean {
        val widthDiff = abs(newWidth - oldWidth)
        val heightDiff = abs(newHeight - oldHeight)
        return widthDiff > MIN_SIZE_CHANGE_THRESHOLD || heightDiff > MIN_SIZE_CHANGE_THRESHOLD
    }

    private fun debounceLayoutChange(action: () -> Unit) {
        debounceJob?.cancel()
        debounceJob = lifecycleScope.launch {
            delay(DEBOUNCE_TIME)
            action()
        }
    }

    private fun adjustSurfaceViewSize() {
        val parent = binding.root
        val surfaceView = binding.mcuSurfaceView
        if (parent.width <= 0 || parent.height <= 0) return
        val (targetWidth, targetHeight) = viewModel.calculateAspectRatio(
            parent.width, parent.height
        )
        try {
            val constraintSet = ConstraintSet()
            constraintSet.clone(parent)
            constraintSet.constrainWidth(surfaceView.id, targetWidth)
            constraintSet.constrainHeight(surfaceView.id, targetHeight)
            constraintSet.centerHorizontally(surfaceView.id, ConstraintSet.PARENT_ID)
            constraintSet.centerVertically(surfaceView.id, ConstraintSet.PARENT_ID)
            constraintSet.applyTo(parent)
            AppLogger.getInstance().d("$TAG 调整SurfaceView尺寸")
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG 调整SurfaceView尺寸失败: ${e.message}")
        }
    }

    private fun setupLayoutViewListeners() {
        AppLogger.getInstance().d("$TAG: 设置双击监听器")
        binding.mcuLayoutView.setOnRectDoubleClickListener(object :
            McuLayoutView.OnRectDoubleClickListener {
            override fun onEnterFullScreen(userId: String, streamType: Int) {
                AppLogger.getInstance().d("$TAG 进入全屏: userId=$userId, streamType=$streamType")
                lastFullscreenTime = System.currentTimeMillis()
                viewModel.enterFullScreen(userId, streamType, binding.mcuSurfaceView)
            }

            override fun onExitFullScreen(userId: String) {
                AppLogger.getInstance().d("$TAG 退出全屏: userId=$userId")
                viewModel.exitFullScreen(binding.mcuSurfaceView)
            }
        })
    }

    fun exitFullScreen(userId: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFullscreenTime < FULLSCREEN_DEBOUNCE_TIME) {
            AppLogger.getInstance().d("$TAG 忽略快速的退出全屏请求，防止误触发")
            return
        }
        AppLogger.getInstance().d("$TAG 通过Fragment退出全屏 userId=$userId")
        viewModel.exitFullScreen(binding.mcuSurfaceView)
        binding.mcuLayoutView.post {
            binding.mcuLayoutView.visibility = ViewGroup.VISIBLE
            binding.mcuLayoutView.alpha = 0f
            binding.mcuLayoutView.animate().alpha(1f).setDuration(200).start()
        }
    }

    override fun initData() {
        // 已转移至observeViewModel
    }

    private fun setupOtherObservers() {
        observerJobs.clear()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                observerJobs.add(launch(coroutineExceptionHandler) {
                    viewModel.currentRenderUserId.collect { userId ->
                        AppLogger.getInstance().d("$TAG 当前渲染用户ID变更: $userId")
                        val currentTime = System.currentTimeMillis()
                        if (userId == null && currentTime - lastFullscreenTime < FULLSCREEN_DEBOUNCE_TIME) {
                            AppLogger.getInstance().d("$TAG 忽略快速的全屏状态重置")
                            return@collect
                        }
                        updateUiForRenderingMode(userId != null)
                    }
                })
            }
        }
    }

    private fun updateUiForRenderingMode(isFullScreen: Boolean) {
        binding.mcuLayoutView.visibility =
            if (isFullScreen) ViewGroup.INVISIBLE else ViewGroup.VISIBLE
        if (isFullScreen) {
            binding.mcuLayoutView.animate().alpha(0f).setDuration(200).start()
        } else {
            binding.mcuLayoutView.alpha = 0f
            binding.mcuLayoutView.animate().alpha(1f).setDuration(200).start()
        }
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

    fun getMcuLayoutView(): McuLayoutView? {
        return view?.findViewById(R.id.mcuLayoutView)
    }

    fun release() {
        QRtcEngine.get().removeCanvas(QCanvas(binding.mcuSurfaceView))
        binding.mcuSurfaceView.release()
    }

    companion object {
        fun newInstance() = McuMainFragment()
        private const val TAG = "McuMainFragment"
        const val DEBOUNCE_TIME = 150L
        const val MIN_SIZE_CHANGE_THRESHOLD = 4
        const val FULLSCREEN_DEBOUNCE_TIME = 800L
    }
}