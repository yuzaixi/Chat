package com.huidiandian.meeting.mcu.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.geedow.netprotocol.basicDataStructure.JNIView
import com.bifrost.rtcengine.entity.QSurfaceView
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.mcu.bean.McuLayoutRect
import com.huidiandian.meeting.mcu.bean.StreamInfo
import com.huidiandian.meeting.mcu.repository.IMcuMainRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

class McuMainViewModel(
    private val repository: IMcuMainRepository,
) : ViewModel() {

    private val _layoutRects = MutableStateFlow<List<McuLayoutRect>>(emptyList())
    val layoutRects: StateFlow<List<McuLayoutRect>> get() = _layoutRects.asStateFlow()

    private val _currentRenderUserId = MutableStateFlow<String?>(null)
    val currentRenderUserId: StateFlow<String?> get() = _currentRenderUserId.asStateFlow()

    // 一次性事件流，避免事件丢失
    private val _mcuFirstFrame = MutableSharedFlow<StreamInfo>(replay = 1, extraBufferCapacity = 64)
    val mcuFirstFrame: SharedFlow<StreamInfo> get() = _mcuFirstFrame.asSharedFlow()

    private var _currentRenderJob: Job? = null
    private val _fullScreenLock = AtomicBoolean(false)
    private val _allJobs = mutableListOf<Job>()

    companion object {
        private const val TAG = "McuMainViewModel"
        private const val OPERATION_TIMEOUT_MS = 10000L
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val ASPECT_RATIO_WIDTH = 16f
        private const val ASPECT_RATIO_HEIGHT = 9f
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            AppLogger.getInstance().e("$TAG 协程异常: ${throwable.stackTraceToString()}")
        }
    }

    init {
        collectRepositoryEvents()
    }

    /** 保持16:9比例的尺寸计算 */
    fun calculateAspectRatio(containerWidth: Int, containerHeight: Int): Pair<Int, Int> {
        if (containerWidth <= 0 || containerHeight <= 0) return 0 to 0
        val containerRatio = containerWidth.toFloat() / containerHeight
        val targetRatio = ASPECT_RATIO_WIDTH / ASPECT_RATIO_HEIGHT
        return if (containerRatio > targetRatio) {
            val h = containerHeight
            val w = (h * targetRatio).toInt()
            w to h
        } else {
            val w = containerWidth
            val h = (w / targetRatio).toInt()
            w to h
        }
    }

    /** 统一收集仓库事件 */
    private fun collectRepositoryEvents() {
        // MCU布局变化
        _allJobs += viewModelScope.launch(Dispatchers.Main + coroutineExceptionHandler) {
            repository.layoutChangedEvent.collect { jniRects -> processLayoutUpdate(jniRects) }
        }
        // MCU首帧事件
        _allJobs += viewModelScope.launch(Dispatchers.Main + coroutineExceptionHandler) {
            repository.mcuFirstFrame.collect {
                AppLogger.getInstance().d("$TAG 收到MCU首帧回调: $it")
                _mcuFirstFrame.emit(it)
            }
        }
    }

    /** 布局更新处理 */
    private fun processLayoutUpdate(jniRects: List<JNIView>) {
        if (jniRects.isEmpty()) return
        _allJobs += viewModelScope.launch(coroutineExceptionHandler) {
            try {
                val mcuLayoutRects = withContext(Dispatchers.Default) {
                    jniRects.map { McuLayoutRect.fromJNIRect(it, VIDEO_WIDTH, VIDEO_HEIGHT) }
                }
                _layoutRects.value = mcuLayoutRects
            } catch (e: Exception) {
                AppLogger.getInstance().e("$TAG 布局更新处理失败: ${e.message}")
            }
        }
    }

    /** 渲染本地预览（超时保护） */
    fun renderLocalPreview(surfaceView: QSurfaceView) {
        _currentRenderJob?.cancel()
        _currentRenderJob = viewModelScope.launch(coroutineExceptionHandler) {
            try {
                withTimeout(OPERATION_TIMEOUT_MS) {
                    if (!repository.renderLocalPreview(surfaceView)) {
                        AppLogger.getInstance().w("$TAG 本地预览渲染失败")
                    }
                }
            } catch (e: Exception) {
                AppLogger.getInstance().e("$TAG 本地预览渲染超时或异常: ${e.message}")
            }
        }
    }

    /** MCU渲染切换（超时保护） */
    fun switchToMcuRendering(surfaceView: QSurfaceView) {
        _currentRenderJob?.cancel()
        _currentRenderJob = viewModelScope.launch(coroutineExceptionHandler) {
            try {
                withTimeout(OPERATION_TIMEOUT_MS) {
                    if (!repository.renderMcuScreen(surfaceView)) {
                        AppLogger.getInstance().w("$TAG MCU渲染切换失败")
                    }
                }
            } catch (e: Exception) {
                AppLogger.getInstance().e("$TAG MCU渲染切换超时或异常: ${e.message}")
            }
        }
    }

    /** 进入全屏模式（加锁，超时保护） */
    fun enterFullScreen(userId: String, streamType: Int, surfaceView: QSurfaceView) {
        if (userId.isEmpty() || !_fullScreenLock.compareAndSet(false, true)) return
        _currentRenderUserId.value = userId
        _currentRenderJob?.cancel()
        _currentRenderJob = viewModelScope.launch(coroutineExceptionHandler) {
            try {
                withTimeout(OPERATION_TIMEOUT_MS) {
                    if (!repository.switchToFullScreenRender(userId, streamType, surfaceView)) {
                        _currentRenderUserId.value = null
                    }
                }
            } catch (e: Exception) {
                _currentRenderUserId.value = null
                AppLogger.getInstance().e("$TAG 进入全屏异常: ${e.message}")
            } finally {
                _fullScreenLock.set(false)
            }
        }
    }

    /** 退出全屏模式（加锁，超时保护） */
    fun exitFullScreen(surfaceView: QSurfaceView) {
        if (!_fullScreenLock.compareAndSet(false, true)) return
        _currentRenderJob?.cancel()
        _currentRenderUserId.value = null
        _currentRenderJob = viewModelScope.launch(coroutineExceptionHandler) {
            try {
                withTimeout(OPERATION_TIMEOUT_MS) {
                    if (!repository.renderMcuScreen(surfaceView)) {
                        AppLogger.getInstance().w("$TAG 退出全屏模式失败")
                    }
                }
            } catch (e: Exception) {
                AppLogger.getInstance().e("$TAG 退出全屏模式超时或异常: ${e.message}")
            } finally {
                _fullScreenLock.set(false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _currentRenderJob?.cancel()
        _currentRenderJob = null
        _allJobs.forEach { it.cancel() }
        _allJobs.clear()
        repository.release()
    }
}