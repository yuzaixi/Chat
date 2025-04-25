package com.huidiandian.meeting.mcu.util

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View.GONE
import android.view.View.VISIBLE
import com.bifrost.rtcengine.QRtcEngine
import com.bifrost.rtcengine.entity.QCanvas
import com.bifrost.rtcengine.entity.QParticipant
import com.huidiandian.meeting.base.client.arguments.JNIEnum
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.base.util.CircleTextImageViewUtil
import com.huidiandian.meeting.meeting.R
import com.huidiandian.meeting.meeting.databinding.LayoutPreviewFloatViewBinding
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.anim.DefaultAnimator
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern

class LocalFloatViewManager(val context: Context, val userInfo: QParticipant?) {

    companion object {
        // 悬浮窗的标签
        const val PREVIEW_FLOAT_TAG = "preview_float_tag"
        private const val TAG = "LocalFloatViewManager"
    }

    private var cachedClipDrawable: ClipDrawable? = null

    // 本地预览悬浮窗的视图绑定对象，使用 lazy 初始化
    private val layoutPreviewFloatViewBinding by lazy {
        LayoutPreviewFloatViewBinding.inflate(LayoutInflater.from(context))
    }

    init {
        AppLogger.getInstance().d("$TAG 初始化本地预览悬浮窗 . userInfo: $userInfo")
        setupPreviewFloatView()
    }

    /**
     * 初始化并显示本地预览的小悬浮窗。
     * 使用 EasyFloat 库实现。
     */
    private fun setupPreviewFloatView() {
        AppLogger.getInstance().d("$TAG 设置预览悬浮窗")
        EasyFloat.with(context)
            .setLayout(layoutPreviewFloatViewBinding.root) { /* 可在此处对悬浮窗视图进行额外设置 */ }
            .setShowPattern(ShowPattern.CURRENT_ACTIVITY) // 仅在当前 Activity 显示
            .setSidePattern(SidePattern.RESULT_HORIZONTAL) // 设置吸附模式为水平方向结果
            .setGravity(Gravity.START or Gravity.TOP, 0, 50) // 初始位置在左上角，带偏移
            .setTag(PREVIEW_FLOAT_TAG) // 设置标签，方便查找和管理
            .setDragEnable(true) // 允许拖拽
            .setAnimator(DefaultAnimator()) // 使用默认的出入动画
            .registerCallbacks(
                FloatViewHandler(
                    layoutPreviewFloatViewBinding, PREVIEW_FLOAT_TAG
                )
            ) // 注册悬浮窗回调
            .show() // 显示悬浮窗
        userInfo?.let {
            updateUserInfo(it.userInfo.name, it.userInfo.icon)
            updateMicState(it.micStatus)
            updateCameraState(it.cameraStatus)
            AppLogger.getInstance().d("$TAG 初始化用户信息: ${it.userInfo.name}, ${it.userInfo.icon}")
        }
    }

    fun updateUserInfo(name: String?, icon: String?) {
        AppLogger.getInstance().d("$TAG 更新用户信息: name=$name, icon=$icon")
        layoutPreviewFloatViewBinding.tvName.text = name
        layoutPreviewFloatViewBinding.ivPhoto
        CircleTextImageViewUtil.setViewData(
            context,
            layoutPreviewFloatViewBinding.ivPhoto,
            icon,
            name,
            JNIEnum.EnAccountType.ACCOUNT_SOFT_TERMIANL,
            true
        )
    }

    fun updateMicState(isMicEnabled: Boolean) {
        AppLogger.getInstance().d("$TAG 更新麦克风状态: $isMicEnabled")
        layoutPreviewFloatViewBinding.ivMic.setImageResource(if (isMicEnabled) R.drawable.layer_icon_mic_opened else R.drawable.icon_mic_closed)
        onIvMicDrawableChanged()
    }

    fun updateCameraState(isCameraEnabled: Boolean) {
        AppLogger.getInstance().d("$TAG 更新摄像头状态: $isCameraEnabled")
        layoutPreviewFloatViewBinding.localPreviewSurface.visibility =
            if (isCameraEnabled) VISIBLE else GONE
        layoutPreviewFloatViewBinding.ivPhoto.visibility = if (isCameraEnabled) GONE else VISIBLE
        layoutPreviewFloatViewBinding.vPhotoBackground.visibility =
            if (isCameraEnabled) GONE else VISIBLE
    }

    private fun cacheClipDrawableIfNeeded() {
        AppLogger.getInstance().d("$TAG 缓存 ClipDrawable")
        // 只在缓存为空时查找并缓存
        if (cachedClipDrawable == null) {
            val drawable = layoutPreviewFloatViewBinding.ivMic.drawable
            cachedClipDrawable = if (drawable is LayerDrawable) {
                drawable.findDrawableByLayerId(android.R.id.progress) as? ClipDrawable
            } else {
                null
            }
        }
    }

    fun onIvMicDrawableChanged() {
        AppLogger.getInstance().d("$TAG 更新麦克风图标")
        cachedClipDrawable = null
        cacheClipDrawableIfNeeded()
    }

    /**
     * 更新麦克风音量
     * @param volume 音量值，范围 0-100
     */
    fun updateMicVolume(volume: Int) {
        AppLogger.getInstance().d("$TAG 更新麦克风音量: $volume")
        cacheClipDrawableIfNeeded()
        cachedClipDrawable?.let {
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
                // 可选：打印日志
            }
        }
    }

    fun updatePreviewState(isPreview: Boolean) {
        AppLogger.getInstance().i("控制预览浮窗可见性: $isPreview")
        // 检查悬浮窗是否已创建，未创建则创建
        if (EasyFloat.getFloatView(PREVIEW_FLOAT_TAG) == null) {
            AppLogger.getInstance().i("预览浮窗不存在，创建新的")
            setupPreviewFloatView()
        }
        // 根据 preview 状态添加或移除本地预览渲染
        if (isPreview) {
            AppLogger.getInstance().i("添加本地预览到悬浮窗")
            // 确保 SurfaceView 准备好后再添加预览
            layoutPreviewFloatViewBinding.localPreviewSurface.post {
                QRtcEngine.get()
                    .addPreview(QCanvas(layoutPreviewFloatViewBinding.localPreviewSurface))
            }
        } else {
            AppLogger.getInstance().i("从悬浮窗移除本地预览")
            QRtcEngine.get()
                .removePreview(QCanvas(layoutPreviewFloatViewBinding.localPreviewSurface))
        }
    }

    fun release() {
        try {
            // 释放预览 SurfaceView
            AppLogger.getInstance().d("释放预览 SurfaceView")
            layoutPreviewFloatViewBinding.localPreviewSurface.release()
        } catch (e: Exception) {
            AppLogger.getInstance().e("释放预览 SurfaceView 时发生异常: ${e.message}")
        }
        // 销毁本地预览悬浮窗
        EasyFloat.getFloatView(PREVIEW_FLOAT_TAG)?.let {
            AppLogger.getInstance().i("销毁预览悬浮窗")
            EasyFloat.dismiss(PREVIEW_FLOAT_TAG)
        }
    }

}