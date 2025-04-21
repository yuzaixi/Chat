package com.huidiandian.meeting.mcu.util

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import com.huidiandian.meeting.base.util.AppLogger

/**
 * 手势处理组件
 * 用于解决触摸事件冲突，处理单击和双击
 */
class MeetingGestureHandler(
    private val doubleTapListener: () -> Unit,
    private val singleTapListener: () -> Unit,
    private val menuAreaChecker: (Float, Float) -> Boolean,
) {
    private val tag = "MeetingGestureHandler"
    private var lastTapTime = 0L
    private val doubleTapTimeout = 300L
    private var pendingSingleTap = false
    private var pendingTapX = 0f
    private var pendingTapY = 0f
    private val handler = Handler(Looper.getMainLooper())

    // 单击任务
    private val singleTapRunnable = Runnable {
        // 单击确认（未发生双击）
        if (pendingSingleTap) {
            pendingSingleTap = false
            AppLogger.getInstance().d("$tag: 执行单击操作 x=$pendingTapX, y=$pendingTapY")
            singleTapListener()
        }
    }

    private var fragmentVisibilityChecker: (() -> Boolean)? = null

    fun setFragmentVisibilityChecker(checker: () -> Boolean) {
        this.fragmentVisibilityChecker = checker
    }

    /**
     * 处理触摸事件
     * @return 是否消费了事件
     */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        // 检查Fragment可见性
        if (fragmentVisibilityChecker?.invoke() == true) {
            return false
        }
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                // 不做特殊处理
                AppLogger.getInstance().d("$tag: ACTION_DOWN x=${ev.x}, y=${ev.y}")
                return false
            }

            MotionEvent.ACTION_UP -> {
                AppLogger.getInstance().d("$tag: ACTION_UP x=${ev.x}, y=${ev.y}")

                // 检查是否点击在菜单区域
                if (menuAreaChecker(ev.x, ev.y)) {
                    AppLogger.getInstance().d("$tag: 点击在菜单区域，不处理手势")
                    return false
                }

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime <= doubleTapTimeout) {
                    // 双击处理
                    AppLogger.getInstance().d("$tag: 检测到双击")
                    handler.removeCallbacks(singleTapRunnable)
                    pendingSingleTap = false
                    lastTapTime = 0
                    doubleTapListener()
                    return true
                } else {
                    // 可能是单击，但需要等待以确认不是双击的开始
                    pendingSingleTap = true
                    pendingTapX = ev.x
                    pendingTapY = ev.y
                    lastTapTime = currentTime
                    AppLogger.getInstance().d("$tag: 可能是单击，延迟处理")
                    handler.postDelayed(singleTapRunnable, doubleTapTimeout)
                    return false
                }
            }
        }
        return false
    }

    /**
     * 释放资源
     */
    fun destroy() {
        AppLogger.getInstance().d("$tag: 释放资源")
        handler.removeCallbacksAndMessages(null)
    }
}