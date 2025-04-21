package com.huidiandian.meeting.mcu.util

import android.view.MotionEvent
import android.view.View
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.base.util.ScreenUtils
import com.huidiandian.meeting.core.extension.setOnSingleClickListener
import com.huidiandian.meeting.meeting.R
import com.huidiandian.meeting.meeting.databinding.LayoutPreviewFloatViewBinding
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.interfaces.OnFloatCallbacks

class FloatViewHandler(
    val layoutPreviewFloatViewBinding: LayoutPreviewFloatViewBinding,
    val floatTag: String,
) :
    OnFloatCallbacks {

    private var downX = 0f
    private var downTime = 0L
    private val swipeThreshold = 150 // 滑动最小距离
    private val timeThreshold = 250  // 滑动最大时间(ms)
    private var currentEnd = END_LEFT // 当前吸附边
    private var isHiding = false      // 防止动画过程中重复触发

    private companion object {
        const val END_LEFT = 0
        const val END_RIGHT = 1
    }

    override fun touchEvent(view: View, event: MotionEvent) {
        if (isHiding) return // 禁止动画过程中滑动
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downTime = System.currentTimeMillis()
            }

            MotionEvent.ACTION_UP -> {
                val upX = event.rawX
                val upTime = System.currentTimeMillis()
                val dx = upX - downX
                val dt = upTime - downTime
                if (dt < timeThreshold && kotlin.math.abs(dx) > swipeThreshold) {
                    if (currentEnd == END_LEFT && dx < 0) {
                        AppLogger.getInstance()
                            .d("FloatShrinkHandler 吸附边:左, 左滑，触发隐藏动画，同时禁止左右滑动")
                        switchFloatViewState(toLeft = true)
                    } else if (currentEnd == END_RIGHT && dx > 0) {
                        AppLogger.getInstance()
                            .d("FloatShrinkHandler 吸附边:右, 右滑，触发隐藏动画，同时禁止左右滑动")
                        switchFloatViewState(toLeft = false)
                    } else {
                        AppLogger.getInstance()
                            .d("FloatShrinkHandler 吸附边:${if (currentEnd == END_LEFT) "左" else "右"}, 非有效滑动方向，不触发 dx=$dx")
                    }
                }
            }
        }
    }

    private fun switchFloatViewState(toLeft: Boolean) {
        if (toLeft) {
            hideByLeft()
        } else {
            hideByRight()
        }
    }

    private fun hideByRight() {
        val floatView = layoutPreviewFloatViewBinding.clPreview
        val arrowView = layoutPreviewFloatViewBinding.ivArrow
        floatView.animate()
            .translationX(floatView.width.toFloat()) // 移动到父布局外
            .setDuration(300)
            .withEndAction {
                floatView.visibility = View.GONE
                arrowView.visibility = View.VISIBLE
                EasyFloat.updateFloat(floatTag)
            }
            .start()
    }

    private fun hideByLeft() {
        val floatView = layoutPreviewFloatViewBinding.clPreview
        val arrowView = layoutPreviewFloatViewBinding.ivArrow
        floatView.animate()
            .translationX(-floatView.width.toFloat())
            .setDuration(300)
            .withEndAction {
                floatView.visibility = View.GONE
                arrowView.visibility = View.VISIBLE
            }
            .start()
    }

    private fun showByLeft() {
        val floatView = layoutPreviewFloatViewBinding.clPreview
        val arrowView = layoutPreviewFloatViewBinding.ivArrow
        arrowView.visibility = View.GONE
        floatView.animate()
            .translationX(0f) // 移回到屏幕内
            .setDuration(300)
            .withStartAction {
                floatView.visibility = View.VISIBLE
            }
            .start()
    }

    private fun showByRight() {
        val floatView = layoutPreviewFloatViewBinding.clPreview
        val arrowView = layoutPreviewFloatViewBinding.ivArrow
        arrowView.visibility = View.GONE
        floatView.animate()
            .translationX(0f) // 移回到屏幕内
            .setDuration(300)
            .withStartAction {
                floatView.visibility = View.VISIBLE
            }
            .start()
    }

    override fun createdResult(isCreated: Boolean, msg: String?, view: View?) {
        layoutPreviewFloatViewBinding.ivArrow.setOnSingleClickListener {
            if (currentEnd == END_LEFT) {
                AppLogger.getInstance().d("FloatShrinkHandler 吸附边:左, 点击，触发显示动画")
                showByLeft()
            } else {
                AppLogger.getInstance().d("FloatShrinkHandler 吸附边:右, 点击，触发显示动画")
                showByRight()
            }
        }
    }

    override fun dismiss() {}
    override fun drag(view: View, event: MotionEvent) {}

    override fun dragEnd(view: View) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0]
        val screenWidth = ScreenUtils.screenWidth
        if (x < 200) {
            currentEnd = END_LEFT
        } else if (x > screenWidth / 2) {
            currentEnd = END_RIGHT
        }
        layoutPreviewFloatViewBinding.ivArrow.setImageResource(
            if (currentEnd == END_LEFT) {
                R.drawable.layer_icon_arrow_right
            } else {
                R.drawable.layer_icon_arrow_left
            }
        )
        AppLogger.getInstance()
            .d("FloatShrinkHandler dragEnd 位置：$x 吸附边:${if (currentEnd == END_LEFT) "左" else "右"}")
    }

    override fun hide(view: View) {}
    override fun show(view: View) {}

}


