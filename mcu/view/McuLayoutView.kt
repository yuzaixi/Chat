package com.huidiandian.meeting.mcu.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isEmpty
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

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * 设置矩形区域数据并更新布局
     */
    fun setRects(newRects: List<McuLayoutRect>) {
        AppLogger.getInstance().d("MCU布局: 设置矩形 ${newRects.size}个")

        if (rects == newRects) return

        rects.clear()
        rects.addAll(newRects)
        updateViewsForCurrentRects()
        requestLayout()
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
        if (isEmpty()) return
        val viewWidth = width
        val viewHeight = height

        // 正常模式：根据矩形数据布局
        rects.forEach { rect ->
            userViews[rect.userId]?.let { view ->
                view.visibility = VISIBLE
                // 计算实际像素位置
                val left = (rect.x * viewWidth).toInt()
                val top = (rect.y * viewHeight).toInt()
                val right = left + (rect.width * viewWidth).toInt()
                val bottom = top + (rect.height * viewHeight).toInt()
                view.layout(left, top, right, bottom)
            }
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
    fun findUserAt(x: Float, y: Float): Pair<String?, Int> {
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
}