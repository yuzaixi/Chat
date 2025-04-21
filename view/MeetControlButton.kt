package com.huidiandian.meeting.mcu.view

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.content.withStyledAttributes
import com.huidiandian.meeting.meeting.R
import com.huidiandian.meeting.meeting.databinding.LayoutMeetControlButtonBinding

class MeetControlButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: LayoutMeetControlButtonBinding =
        LayoutMeetControlButtonBinding.inflate(LayoutInflater.from(context), this, true)

    // 麦克风ClipDrawable引用，用于动态音量显示
    private var micClipDrawable: ClipDrawable? = null

    init {
        context.withStyledAttributes(attrs, R.styleable.MeetControlButton) {
            getString(R.styleable.MeetControlButton_btnText)?.let { setButtonText(it) }
            getResourceId(R.styleable.MeetControlButton_btnImage, -1)
                .takeIf { it != -1 }?.let { setButtonImage(it) }
        }
    }

    fun setButtonText(text: String) {
        binding.btnText.text = text
    }

    fun setButtonTextColor(color: Int) {
        binding.btnText.setTextColor(color)
    }

    /**
     * 设置按钮图标，同时自动更新麦克风ClipDrawable引用
     */
    fun setButtonImage(resId: Int) {
        binding.btnText.setCompoundDrawablesWithIntrinsicBounds(0, resId, 0, 0)
        updateMicClipDrawableIfNeeded(resId)
    }

    /**
     * 根据资源ID判断是否需要更新micClipDrawable
     */
    private fun updateMicClipDrawableIfNeeded(resId: Int) {
        if (resId == R.drawable.layer_icon_mic_opened) {
            val drawable = binding.btnText.compoundDrawables[1]
            micClipDrawable = (drawable as? LayerDrawable)
                ?.findDrawableByLayerId(android.R.id.progress) as? ClipDrawable
        } else {
            micClipDrawable = null
        }
    }

    fun getButtonText(): String? =
        binding.btnText.text.toString().takeIf { it.isNotEmpty() }

    fun getButtonDrawable(): Drawable? =
        binding.btnText.compoundDrawables[1]

    /**
     * 设置麦克风音量级别，仅对麦克风ClipDrawable生效
     * @param volume 音量值[0,100]
     */
    fun setMicVolumeLevel(volume: Int) {
        micClipDrawable?.let {
            val baseHeight = 4000
            val dynamicRange = 10000 - baseHeight
            val level = if (volume <= 0) {
                baseHeight
            } else {
                val pct = volume.coerceIn(0, 100) / 100.0
                val dyn = (pct * pct * dynamicRange).toInt()
                (baseHeight + dyn).coerceIn(baseHeight, 10000)
            }
            try {
                it.level = level
            } catch (e: Exception) {
                // 防御性处理
                micClipDrawable = null
            }
        }
    }
}