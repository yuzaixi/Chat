package com.huidiandian.meeting.mcu.fragment

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import com.bifrost.rtcengine.QRtcEngine
import com.bifrost.rtcengine.constants.QStreamType
import com.bifrost.rtcengine.entity.QCanvas
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.base.vvm.MVVMFragment
import com.huidiandian.meeting.mcu.viewmodel.ScreenShareViewModel
import com.huidiandian.meeting.meeting.databinding.FragmentScreenShareBinding
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * 屏幕共享Fragment，负责显示远程共享的屏幕内容
 */
class ScreenShareFragment : MVVMFragment<FragmentScreenShareBinding, ScreenShareViewModel>() {

    private val logger = AppLogger.getInstance()

    // 使用Koin注入ViewModel，避免反射创建失败
    override val viewModel: ScreenShareViewModel by viewModel()

    override fun initData() {
        super.initData()
        val account = arguments?.getString(ACCOUNT)
        val streamType = arguments?.getInt(STREAM_TYPE)

        if (account.isNullOrEmpty()) {
            logger.e("$TAG 初始化失败: 账号为空")
            return
        }

        viewModel.setupShareStream(account, streamType)

        // 监听布局变化，调整SurfaceView尺寸
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    adjustSurfaceViewRatio()
                    binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        )
    }

    /**
     * 调整SurfaceView尺寸为16:9比例
     */
    private fun adjustSurfaceViewRatio() {
        val parentWidth = binding.root.width
        val parentHeight = binding.root.height

        if (parentWidth <= 0 || parentHeight <= 0) return

        // 计算16:9比例下的尺寸
        val heightByWidth = (parentWidth * 9) / 16
        val widthByHeight = (parentHeight * 16) / 9

        val params = binding.surfaceView.layoutParams

        // 选择合适的尺寸，保证视图完全在父容器内
        if (heightByWidth <= parentHeight) {
            // 以宽度为基准
            params.width = parentWidth
            params.height = heightByWidth
        } else {
            // 以高度为基准
            params.width = widthByHeight
            params.height = parentHeight
        }

        // 居中显示
        if (params is ConstraintLayout.LayoutParams) {
            params.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
            params.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        }

        binding.surfaceView.layoutParams = params
        logger.d("$TAG 设置SurfaceView尺寸: ${params.width}x${params.height}")
    }

    override fun observeViewModel() {
        // 监听首帧渲染事件
        viewModel.firstFrame.observe(viewLifecycleOwner) { firstFrameReceived ->
            if (firstFrameReceived) {
                setupRemoteVideo()
            }
        }

        // 监听订阅状态
        viewModel.subscribeState.observe(viewLifecycleOwner) { state ->
            logger.d("$TAG 订阅状态变更: $state")
        }
    }

    /**
     * 设置远程视频渲染
     */
    private fun setupRemoteVideo() {
        try {
            val engine = QRtcEngine.get()
            val roomId = engine.meetingInfo.room.id
            val account = viewModel.getAccount()
            val streamType = viewModel.getStreamType()
            if (roomId.isNullOrEmpty() || account.isEmpty()) {
                logger.e("$TAG 设置视频渲染失败: 房间ID或账号为空")
                return
            }
            // 配置画布渲染参数
            val canvas = QCanvas(binding.surfaceView)
            engine.addCanvas(account, roomId, streamType, canvas)
            logger.d("$TAG 添加画布成功: 账号=$account, 房间=$roomId, 流类型=$streamType")
        } catch (e: Exception) {
            logger.e("$TAG 设置远程视频渲染失败: ${e.message}")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕方向变化时重新调整尺寸
        adjustSurfaceViewRatio()
    }

    override fun injectBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): FragmentScreenShareBinding {
        return FragmentScreenShareBinding.inflate(inflater, container, false)
    }

    override fun onDestroy() {
        try {
            // 清理视图资源
            val account = viewModel.getAccount()
            val streamType = viewModel.getStreamType()
            val engine = QRtcEngine.get()
            val roomId = engine.meetingInfo.room.id

            if (account.isNotEmpty() && !roomId.isNullOrEmpty()) {
                // 移除画布
                val canvas = QCanvas(binding.surfaceView)
                engine.removeCanvas(canvas)
                logger.d("$TAG 已移除画布: 账号=$account, 流类型=$streamType")
            }

            // 释放SurfaceView资源
            binding.surfaceView.release()
            logger.d("$TAG 已释放SurfaceView资源")
        } catch (e: Exception) {
            logger.e("$TAG 清理资源时发生错误: ${e.message}")
        } finally {
            super.onDestroy()
        }
    }

    companion object {
        private const val TAG = "ScreenShareFragment"
        const val ACCOUNT = "account"
        const val STREAM_TYPE = "stream_type"

        /**
         * 创建屏幕共享Fragment实例
         * @param account 共享账号
         * @param streamType 流类型，默认为共享流
         * @return ScreenShareFragment实例
         */
        fun newInstance(
            account: String,
            streamType: Int = QStreamType.SHARE.ordinal,
        ): ScreenShareFragment {
            return ScreenShareFragment().apply {
                arguments = Bundle().apply {
                    putString(ACCOUNT, account)
                    putInt(STREAM_TYPE, streamType)
                }
            }
        }
    }
}