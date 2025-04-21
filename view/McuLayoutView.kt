package com.huidiandian.meeting.mcu.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.mcu.bean.McuLayoutRect

/**
 * 自定义浮层 ViewGroup，用于处理多视频布局的事件交互
 * 修复版: 增强双击检测和全屏退出功能，解决滑动冲突
 */
class McuLayoutView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    // 存储矩形区域数据
    private val rects = mutableListOf<McuLayoutRect>()
    private val userViews = mutableMapOf<String, View>()
    private var singleTapListener: OnRectClickListener? = null
    private var doubleTapListener: OnRectDoubleClickListener? = null
    private var isFullScreen = false
    private var fullScreenUserId: String? = null
    private var lastRects = mutableListOf<McuLayoutRect>()
    private var dataUpdatedDuringFullScreen = false

    // 滑动冲突处理参数
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialX = 0f
    private var initialY = 0f
    private var isHorizontalScrollDetected = false

    // 添加一个防止事件重复处理的标记
    private var isFullScreenOperationInProgress = false
    private var lastOperationTime = 0L

    /**
     * 优化的手势检测器配置
     */
    private val gestureDetector: GestureDetector by lazy {
        GestureDetector(context, gestureListener).apply {
            setIsLongpressEnabled(false) // 禁用长按，避免干扰双击检测
        }
    }

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            AppLogger.getInstance().d("MCU布局: onDown x=${e.x}, y=${e.y}")
            return true // 总是返回true以继续接收后续事件
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastOperationTime < OPERATION_COOLDOWN) {
                AppLogger.getInstance().d("MCU布局: 忽略频繁的双击事件")
                return true
            }

            lastOperationTime = currentTime

            AppLogger.getInstance()
                .d("MCU布局: 检测到双击事件 x=${e.x}, y=${e.y}, 全屏状态=$isFullScreen")

            if (isFullScreenOperationInProgress) {
                AppLogger.getInstance().d("MCU布局: 全屏操作正在进行中，忽略本次双击")
                return true
            }

            isFullScreenOperationInProgress = true

            if (isFullScreen) {
                AppLogger.getInstance().d("MCU布局: 双击退出全屏触发")
                performExitFullScreen()
            } else {
                val (userId, streamType) = findUserAt(e.x, e.y)
                if (userId != null) {
                    AppLogger.getInstance().d("MCU布局: 双击进入全屏 userId=$userId")
                    performEnterFullScreen(userId, streamType)
                }
            }

            // 一段时间后重置操作状态
            postDelayed({ isFullScreenOperationInProgress = false }, OPERATION_COOLDOWN)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 只处理非全屏模式下的单击
            if (!isFullScreen) {
                val (userId, _) = findUserAt(e.x, e.y)
                if (userId != null) {
                    rects.find { it.userId == userId }?.let {
                        AppLogger.getInstance().d("MCU布局: 单击确认 userId=$userId")
                        singleTapListener?.onRectClick(it)
                    }
                }
            }
            return true
        }
    }

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * 执行进入全屏操作，确保在UI线程中运行
     */
    private fun performEnterFullScreen(userId: String, streamType: Int) {
        post {
            isFullScreen = true
            fullScreenUserId = userId

            requestLayout()
            invalidate()

            doubleTapListener?.let {
                AppLogger.getInstance().d("MCU布局: 开始调用 onEnterFullScreen userId=$userId")
                it.onEnterFullScreen(userId, streamType)
            } ?: AppLogger.getInstance().e("MCU布局: doubleTapListener 为 null")

            AppLogger.getInstance().d("MCU布局: 成功执行进入全屏 userId=$userId")
        }
    }

    /**
     * 执行退出全屏操作，确保在UI线程中运行
     */
    private fun performExitFullScreen() {
        val userId = fullScreenUserId ?: run {
            AppLogger.getInstance().e("MCU布局: 退出全屏失败，userId为空")
            return
        }

        post {
            isFullScreen = false
            fullScreenUserId = null

            // 如果全屏期间数据没有更新，使用保存的旧布局
            if (!dataUpdatedDuringFullScreen && lastRects.isNotEmpty()) {
                rects.clear()
                rects.addAll(lastRects)
            }
            dataUpdatedDuringFullScreen = false

            // 更新视图
            updateViewsForCurrentRects()
            requestLayout()
            invalidate()

            // 通知外部
            doubleTapListener?.onExitFullScreen(userId)

            // 打印日志确认执行
            AppLogger.getInstance().d("MCU布局: 成功执行退出全屏 userId=$userId")
        }
    }

    /**
     * 设置矩形区域数据并更新布局
     */
    fun setRects(newRects: List<McuLayoutRect>) {
        AppLogger.getInstance().d("MCU布局: 设置矩形 ${newRects.size}个, 全屏状态=$isFullScreen")

        if (rects == newRects) return

        rects.clear()
        rects.addAll(newRects)

        if (isFullScreen) {
            dataUpdatedDuringFullScreen = true
            return
        }

        updateViewsForCurrentRects()
        requestLayout()
    }

    /**
     * 优化的触摸事件处理，解决与ViewPager2的滑动冲突
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 当水平滑动被检测到时，不拦截事件
        if (isHorizontalScrollDetected && !isFullScreen) {
            AppLogger.getInstance().d("MCU布局: 水平滑动被检测到，放行事件")
            return false
        }

        // 记录事件类型，便于调试
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                AppLogger.getInstance()
                    .d("MCU布局: onTouchEvent ACTION_DOWN x=${event.x}, y=${event.y}, 全屏=$isFullScreen")
            }

            MotionEvent.ACTION_UP -> {
                // 重置滑动检测状态
                isHorizontalScrollDetected = false
                AppLogger.getInstance()
                    .d("MCU布局: onTouchEvent ACTION_UP x=${event.x}, y=${event.y}, 全屏=$isFullScreen")
            }
        }

        // 将事件传递给手势检测器
        val handled = gestureDetector.onTouchEvent(event)

        // 全屏模式下，优先考虑手势处理结果
        if (isFullScreen) {
            return true // 全屏模式下总是消费事件，确保双击可以触发退出
        }

        return handled || super.onTouchEvent(event)
    }

    /**
     * 修复版分发事件方法，不强制阻止父视图拦截
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 检测水平滑动
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = ev.x
                initialY = ev.y
                isHorizontalScrollDetected = false

                // 仅在全屏模式时请求父视图不拦截
                if (isFullScreen) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                AppLogger.getInstance()
                    .d("MCU布局: dispatchTouchEvent DOWN x=${ev.x}, y=${ev.y}, 全屏=$isFullScreen")
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isHorizontalScrollDetected) {
                    val deltaX = Math.abs(ev.x - initialX)
                    val deltaY = Math.abs(ev.y - initialY)

                    // 如果水平移动距离显著大于垂直移动，判定为水平滑动
                    if (deltaX > touchSlop && deltaX > deltaY * 1.5f) {
                        isHorizontalScrollDetected = true
                        // 水平滑动时，允许父视图拦截
                        parent?.requestDisallowInterceptTouchEvent(false)
                        AppLogger.getInstance().d("MCU布局: 检测到水平滑动，允许父视图拦截")
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 重置状态
                isHorizontalScrollDetected = false
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    /**
     * 修复版拦截方法，水平滑动时不拦截事件
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 处理ACTION_DOWN以初始化滑动检测
        if (ev.action == MotionEvent.ACTION_DOWN) {
            initialX = ev.x
            initialY = ev.y
            isHorizontalScrollDetected = false

            // 让手势检测器也能处理DOWN事件
            gestureDetector.onTouchEvent(ev)
        }
        // 处理ACTION_MOVE进行滑动方向判断
        else if (ev.action == MotionEvent.ACTION_MOVE && !isHorizontalScrollDetected) {
            val deltaX = Math.abs(ev.x - initialX)
            val deltaY = Math.abs(ev.y - initialY)

            // 检测明确的水平滑动
            if (deltaX > touchSlop && deltaX > deltaY * 1.5f) {
                isHorizontalScrollDetected = true
                // 不拦截水平滑动
                AppLogger.getInstance().d("MCU布局: onInterceptTouchEvent 检测到水平滑动，不拦截")
                return false
            }
        }

        // 在全屏模式下始终拦截
        if (isFullScreen) {
            return true
        }

        // 对于其他情况，在未检测到水平滑动时拦截
        return !isHorizontalScrollDetected
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
        }

        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (childCount == 0) return

        val viewWidth = width
        val viewHeight = height

        if (isFullScreen && fullScreenUserId != null) {
            // 全屏模式：只显示一个视图
            userViews[fullScreenUserId]?.let { view ->
                view.layout(0, 0, viewWidth, viewHeight)
                view.visibility = View.VISIBLE

                // 隐藏其他视图但不移除它们
                userViews.forEach { (userId, v) ->
                    v.visibility = if (userId == fullScreenUserId) View.VISIBLE else View.INVISIBLE
                }
            }
        } else {
            // 正常模式：根据矩形数据布局
            rects.forEach { rect ->
                userViews[rect.userId]?.let { view ->
                    view.visibility = View.VISIBLE

                    // 计算实际像素位置
                    val left = (rect.x * viewWidth).toInt()
                    val top = (rect.y * viewHeight).toInt()
                    val right = left + (rect.width * viewWidth).toInt()
                    val bottom = top + (rect.height * viewHeight).toInt()

                    view.layout(left, top, right, bottom)
                }
            }
        }
    }

    /**
     * 可选的视觉反馈，帮助用户识别全屏模式
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        // 全屏模式下添加轻微视觉提示
        if (isFullScreen) {
            // 在全屏模式下添加一个非常淡的边框提示
            val paint = Paint().apply {
                color = Color.WHITE
                alpha = 15 // 微弱可见
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRect(5f, 5f, width.toFloat() - 5f, height.toFloat() - 5f, paint)
        }
    }

    /**
     * 根据当前rects更新视图
     */
    private fun updateViewsForCurrentRects() {
        val currentUserIds = rects.map { it.userId }.toSet()
        val viewUserIds = userViews.keys.toSet()

        // 移除不需要的视图
        viewUserIds.minus(currentUserIds).forEach { userId ->
            userViews.remove(userId)?.also {
                removeView(it)
            }
        }

        // 添加新视图
        currentUserIds.minus(viewUserIds).forEach { userId ->
            val placeholderView = View(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = false
                tag = userId
            }
            userViews[userId] = placeholderView
            addView(placeholderView)
        }
    }

    /**
     * 判断触摸点所在的用户区域
     */
    private fun findUserAt(x: Float, y: Float): Pair<String?, Int> {
        val xRatio = x / width
        val yRatio = y / height

        for (rect in rects) {
            if (xRatio >= rect.x && xRatio <= rect.x + rect.width &&
                yRatio >= rect.y && yRatio <= rect.y + rect.height
            ) {
                return Pair(rect.userId, rect.streamType)
            }
        }
        return Pair(null, 0)
    }

    // 公共接口
    fun isInFullScreen(): Boolean = isFullScreen
    fun getFullScreenUserId(): String? = fullScreenUserId

    /**
     * 单击事件监听器接口
     */
    interface OnRectClickListener {
        fun onRectClick(rect: McuLayoutRect)
    }

    fun setOnRectClickListener(listener: OnRectClickListener) {
        this.singleTapListener = listener
    }

    companion object {
        private const val OPERATION_COOLDOWN = 800L
    }

    /**
     * 双击事件监听器接口
     */
    interface OnRectDoubleClickListener {
        fun onEnterFullScreen(userId: String, streamType: Int)
        fun onExitFullScreen(userId: String)
    }

    fun setOnRectDoubleClickListener(listener: OnRectDoubleClickListener) {
        this.doubleTapListener = listener
    }
}