package com.huidiandian.meeting.mcu

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.TextView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.bifrost.rtcengine.QRtcEngine
import com.bifrost.rtcengine.constants.QDeviceState
import com.bifrost.rtcengine.constants.QResolution
import com.bifrost.rtcengine.constants.QRoomRole
import com.bifrost.rtcengine.constants.QStreamType
import com.bifrost.rtcengine.entity.StartPublishParams
import com.gyf.immersionbar.ImmersionBar
import com.huidiandian.meeting.base.application.MiguVideoApplicaition
import com.huidiandian.meeting.base.client.arguments.EnRoomIdType
import com.huidiandian.meeting.base.constant.LiveDataBusConstantBaseModule
import com.huidiandian.meeting.base.constant.LiveDataBusConstantBaseModule.HIDE_LIST
import com.huidiandian.meeting.base.constant.MMKVConstant
import com.huidiandian.meeting.base.constant.RouterUrlManager
import com.huidiandian.meeting.base.dialog.AlertPermissionDialog
import com.huidiandian.meeting.base.permission.OnHHDPermissionCallback
import com.huidiandian.meeting.base.permission.PermissionManager
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.base.util.CircleTextImageViewUtil
import com.huidiandian.meeting.base.util.DialogHelper
import com.huidiandian.meeting.base.util.DialogUtils
import com.huidiandian.meeting.base.util.HDDUtils.copy
import com.huidiandian.meeting.base.util.MMKVUtil
import com.huidiandian.meeting.base.util.ScreenUtils
import com.huidiandian.meeting.base.util.TimeUtil
import com.huidiandian.meeting.base.util.ToastUtil
import com.huidiandian.meeting.base.vvm.MVVMActivity
import com.huidiandian.meeting.base.webview.IntentValues
import com.huidiandian.meeting.core.extension.setOnSingleClickListener
import com.huidiandian.meeting.mcu.adapter.MeetingPagerAdapter
import com.huidiandian.meeting.mcu.bean.McuMeetEvent
import com.huidiandian.meeting.mcu.bean.McuMeetState
import com.huidiandian.meeting.mcu.bean.RecordState
import com.huidiandian.meeting.mcu.bean.SharingState
import com.huidiandian.meeting.mcu.fragment.McuMainFragment
import com.huidiandian.meeting.mcu.fragment.ScreenShareFragment
import com.huidiandian.meeting.mcu.util.LocalFloatViewManager
import com.huidiandian.meeting.mcu.util.MeetingGestureHandler
import com.huidiandian.meeting.mcu.util.ScreenKeepAwakeManager
import com.huidiandian.meeting.mcu.view.MeetMenuView
import com.huidiandian.meeting.mcu.viewmodel.MCUMeetViewModel
import com.huidiandian.meeting.meeting.R
import com.huidiandian.meeting.meeting.ScreenShare.QIScreenSharingListener
import com.huidiandian.meeting.meeting.ScreenShare.QScreenSharingBinder
import com.huidiandian.meeting.meeting.ScreenShare.QScreenSharingService
import com.huidiandian.meeting.meeting.databinding.ActivityMcuMeetBinding
import com.huidiandian.meeting.meeting.event.HideUserListFragment
import com.huidiandian.meeting.meeting.event.ShareScreen
import com.huidiandian.meeting.meeting.room.MeetingInfoPop
import com.huidiandian.meeting.meeting.room.NetAndStreamStatePop
import com.huidiandian.meeting.meeting.room.fragment.ExitMeetingDialog
import com.huidiandian.meeting.meeting.room.fragment.ShareScreenFragment
import com.huidiandian.meeting.meeting.room.meet.MeetingActivity.Companion.CODE_REQUEST_CAPTURE
import com.huidiandian.meeting.meeting.room.meet.MeetingActivity.Companion.CODE_REQUEST_OVERLAY
import com.huidiandian.meeting.meeting.room.meethost.MeetHostFragment
import com.jeremyliao.liveeventbus.LiveEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.min

/**
 * MCU (多点控制单元) 会议界面的 Activity。
 * 负责展示会议视频、处理用户交互、管理会议状态和功能。
 */
@Route(path = RouterUrlManager.MEET_MCU_ACTIVITY) // ARouter 路由注解
class MCUMeetActivity : MVVMActivity<ActivityMcuMeetBinding, MCUMeetViewModel>() {

    companion object {
        private const val TAG = "MCUMeetActivity" // 日志标签
        private const val TOUCH_SLOP = 8f // 判定为滑动的最小触摸距离阈值 (dp)
        private const val SCROLL_RATIO_THRESHOLD = 1.2f // 水平滑动距离与垂直滑动距离的比值阈值，用于区分水平和垂直滑动
        private const val CLICK_THRESHOLD = 500L // 单击事件防抖的时间阈值 (毫秒)
    }

    // 通过 Koin 依赖注入获取 ViewModel 实例
    override val viewModel: MCUMeetViewModel by viewModel()

    // 屏幕常亮管理器，用于在会议期间保持屏幕常亮
    private val screenKeepAwakeManager = ScreenKeepAwakeManager()

    // ViewPager2 的适配器，用于管理主会场和屏幕共享 Fragment
    private lateinit var pagerAdapter: MeetingPagerAdapter

    // 会议界面的手势处理器，用于处理单击和双击事件
    private lateinit var gestureHandler: MeetingGestureHandler

    // 参会者列表 Fragment
    private var meetHostFragment: MeetHostFragment? = null

    // 网络和流状态弹窗
    private var netAndStreamStatePop: NetAndStreamStatePop? = null

    // 屏幕共享服务的 Binder 对象，用于与 Service 通信
    private var mScreenSharingBinder: QScreenSharingBinder? = null

    // 开始屏幕共享时传递给 RTC 引擎的参数
    private var startPublishParams: StartPublishParams? = null

    // --- 状态变量 ---
    private var downX = 0f // 手指按下的 X 坐标
    private var downY = 0f // 手指按下的 Y 坐标
    private var isHorizontalScroll = false // 标记当前是否正在进行水平滑动
    private var lastClickTime = 0L // 上次有效单击事件的时间戳，用于防抖
    private var joinSystemTimeMillis: Long = 0L // 加入会议时的系统时间戳，用于计算会议时长

    private var localFloatViewManager: LocalFloatViewManager? = null

    /**
     * 注入视图绑定对象。
     * @return ActivityMcuMeetBinding 实例。
     */
    override fun injectBinding() = ActivityMcuMeetBinding.inflate(layoutInflater)

    /**
     * 初始化视图。在 [onCreate] 中调用，[injectBinding] 之后。
     * 配置 UI 元素、设置监听器、初始化管理器等。
     */
    override fun injectView() {
        logD("初始化视图")
        configureVideoResolution() // 配置视频参数
        screenKeepAwakeManager.keepScreenAwake(this) // 保持屏幕常亮
        setupStatusBar() // 设置沉浸式状态栏
        setupGestureHandler() // 设置手势处理器
        setupMenuView() // 初始化底部菜单栏
        setupListener() // 设置其他视图监听器
        setupObservers() // 设置 LiveData 和 Flow 的观察者
        setupEventCollector() // 设置 ViewModel 事件的收集器
        setupScreenCapturePermissionListener() // 设置屏幕捕捉权限请求的监听
        if (localFloatViewManager == null) {
            localFloatViewManager =
                LocalFloatViewManager(this, viewModel.viewState.value.userInfo) // 初始化悬浮窗管理器
        }
    }

    /**
     * 设置通用的视图监听器。
     */
    private fun setupListener() {
        // 点击参会者列表外部区域时，隐藏列表
        binding.constraintHost.setOnClickListener { hideParticipantsFragment() }
    }

    /**
     * 初始化数据。在 [onCreate] 中调用，[injectView] 之后。
     * 获取初始化数据、请求网络、配置 ViewModel 等。
     */
    override fun initData() {
        logD("初始化数据")
        joinSystemTimeMillis = System.currentTimeMillis() // 记录加入会议时间
        with(viewModel) {
            checkRoomStatus() // 检查房间状态
            checkMediaPermissions(this@MCUMeetActivity) // 检查必要的媒体权限
            setupStream() // 初始化 RTC 流相关设置
        }
        setupViewPager() // 初始化 ViewPager
    }


    /**
     * 配置 RTC 引擎的视频参数，特别是屏幕共享的分辨率。
     */
    private fun configureVideoResolution() {
        QRtcEngine.get().apply {
            // 获取当前共享流的视频配置
            setVideoConfig(getVideoConfig(QStreamType.SHARE).also {
                // 将分辨率设置为 720P
                it.resolution = QResolution.RES_720P
            }, QStreamType.SHARE) // 应用修改后的配置到共享流
        }
    }

    /**
     * 设置手势处理器，用于处理单击和双击事件。
     */
    private fun setupGestureHandler() {
        logD("设置手势处理器")
        gestureHandler = MeetingGestureHandler(
            doubleTapListener = { handleDoubleTap() }, // 双击事件回调
            singleTapListener = { singleTapMenuDebounce() }, // 单击事件回调（带防抖）
            // 检查触摸点是否在底部菜单区域内，若是则不处理单击事件（防止冲突）
            menuAreaChecker = { x, y -> binding.meetMenuView.isPointInMenuArea(x, y) }
        )
    }

    /**
     * 处理单击事件（带防抖）。
     * 用于切换底部菜单栏的显示/隐藏状态。
     */
    private fun singleTapMenuDebounce() {
        val now = System.currentTimeMillis()
        // 检查距离上次有效点击是否超过阈值
        if (now - lastClickTime > CLICK_THRESHOLD) {
            lastClickTime = now // 更新上次点击时间
            toggleMenuVisibility() // 切换菜单可见性
        } else {
            logD("点击防抖，忽略单击") // 短时间内重复点击，忽略
        }
    }

    /**
     * 分发触摸事件。
     * 优化处理，优先将单击和双击事件交由 [gestureHandler] 处理，解决与 ViewPager 滑动冲突。
     * @param ev 触摸事件对象。
     * @return 如果事件被消费则返回 true，否则返回 super.dispatchTouchEvent(ev)。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                // 记录按下坐标，重置滑动标记
                downX = ev.x; downY = ev.y; isHorizontalScroll = false
            }

            MotionEvent.ACTION_MOVE -> if (!isHorizontalScroll) { // 如果尚未判定为滑动
                // 计算 X 和 Y 方向的移动距离
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                // 如果移动距离超过阈值，则判断滑动方向
                if (dx > TOUCH_SLOP || dy > TOUCH_SLOP) isHorizontalScroll =
                    dx > dy * SCROLL_RATIO_THRESHOLD // 水平滑动距离大于垂直距离一定比例，则认为是水平滑动
            }

            MotionEvent.ACTION_UP -> if (!isHorizontalScroll // 如果不是滑动事件
                && System.currentTimeMillis() - lastClickTime > CLICK_THRESHOLD // 且满足单击防抖条件
                && gestureHandler.onTouchEvent(ev) == true // 且手势处理器消费了该事件（判定为单击或双击）
            ) return true // 则消费事件，不向下传递
        }
        // 其他情况（滑动、长按等）或手势处理器未消费，交由系统默认处理
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 处理双击事件。
     * 如果当前处于某个用户的全屏模式，则退出全屏。
     */
    private fun handleDoubleTap() {
        getCurrentMainFragment() // 获取当前显示的 McuMainFragment
            ?.getMcuLayoutView() // 获取其内部的视频布局视图
            ?.takeIf { it.isInFullScreen() } // 检查是否处于全屏模式
            ?.getFullScreenUserId() // 获取当前全屏显示的用户 ID
            ?.let { userId -> // 如果获取到用户 ID
                logD("处理双击事件，退出用户 $userId 的全屏")
                getCurrentMainFragment()?.exitFullScreen(userId) // 调用 Fragment 方法退出全屏
            }
    }

    /**
     * 切换底部菜单栏的显示/隐藏状态。
     */
    private fun toggleMenuVisibility() = with(binding.meetMenuView) {
        if (isMenuVisible()) hide() else show()
    }

    /**
     * 设置沉浸式状态栏。
     */
    private fun setupStatusBar() {
        logD("设置状态栏")
        ImmersionBar.with(this)
            .transparentStatusBar() // 设置状态栏透明
            .init() // 初始化
    }

    /**
     * 设置底部菜单视图 ([MeetMenuView])。
     * 包括设置状态栏高度占位、设置菜单项点击监听、设置更多按钮监听、配置共享控制按钮。
     */
    private fun setupMenuView() = with(binding.meetMenuView) {
        logD("设置菜单视图")
        setStateBarHeight(ImmersionBar.getStatusBarHeight(this@MCUMeetActivity)) // 适配沉浸式状态栏
        setOnMenuItemClickListener(createMenuClickListener()) // 设置主菜单项点击监听
        setOnMoreItemClickListener(createMoreItemClickListener()) // 设置更多菜单项点击监听
        setupShareControls() // 配置共享相关的控制按钮
    }

    /**
     * 创建底部菜单栏的 "更多" 菜单项点击监听器。
     * @return MeetMenuView.OnMoreItemClickListener 实例。
     */
    private fun createMoreItemClickListener() = object : MeetMenuView.OnMoreItemClickListener {
        /**
         * 点击音频模式切换按钮。
         * @param audio true 表示切换到纯音频模式，false 表示退出纯音频模式。
         */
        override fun onAudioModeClick(audio: Boolean) {
            logD("更多菜单 - 点击音频模式: $audio")
            viewModel.switchVoiceMode(audio)
        }
    }

    /**
     * 隐藏参会者列表 Fragment。
     * 执行平移动画将 Fragment 移出屏幕，并隐藏 Fragment。
     */
    private fun hideParticipantsFragment() {
        logD("隐藏参会者列表")
        // 启动向右移出动画
        binding.flMeetHost.startAnimation(getSwitchScreenAnimation(false))

        // 隐藏 Fragment
        val fm = supportFragmentManager
        val begin = fm.beginTransaction()
        meetHostFragment?.let { begin.hide(it) }
        begin.commitAllowingStateLoss() // 允许状态丢失提交，避免 Activity 状态保存后操作 Crash
    }

    /**
     * 配置屏幕共享时的控制按钮（位于 [binding.layoutShareLayer]）。
     */
    private fun setupShareControls() = with(binding.layoutShareLayer) {
        logD("设置共享控制按钮")
        llVertical.visibility = GONE // 隐藏竖屏布局（如果存在）
        llHorizontal.visibility = VISIBLE // 显示横屏布局
        // 设置停止共享按钮的点击事件（带防抖）
        llShareStop.setOnSingleClickListener {
            logD("点击停止共享按钮")
            viewModel.toggleSharing() // 调用 ViewModel 处理停止共享逻辑
        }
    }

    /**
     * 设置 ViewModel 单次事件 ([McuMeetEvent]) 的收集器。
     * 使用 `repeatOnLifecycle` 保证仅在 Activity STARTED 状态时收集。
     */
    private fun setupEventCollector() = lifecycleScope.launch {
        logD("设置 ViewModel 事件收集器")
        // repeatOnLifecycle 在 STOPPED 时取消协程，在 STARTED 时重启
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event -> // 收集 ViewModel 发送的事件
                logD("收到 ViewModel 事件: ${event::class.java.simpleName}")
                handleEvent(event) // 处理事件
            }
        }
    }

    /**
     * 处理来自 ViewModel 的各种单次事件 ([McuMeetEvent])。
     * 根据事件类型执行不同的 UI 操作，如显示 Toast、弹窗、请求权限、结束页面等。
     * @param event ViewModel 发送的事件。
     */
    private fun handleEvent(event: McuMeetEvent) {
        when (event) {
            is McuMeetEvent.Error -> ToastUtil.toast(event.message) // 显示错误提示
            is McuMeetEvent.PermissionRequired -> requestPermission(event.permission) // 请求所需权限
            is McuMeetEvent.ShowToast -> ToastUtil.toast(event.message) // 显示普通提示
            is McuMeetEvent.FinishRoom -> finish() // 关闭当前 Activity
            is McuMeetEvent.ShowDialog -> when (event.type) { // 显示特定类型的对话框
                McuMeetEvent.DIALOG_TYPE_SHARE -> showSharePop() // 显示分享弹窗
                McuMeetEvent.DIALOG_TYPE_MIC_BAN -> showMicBanDialog() // 显示被主持人禁麦提示
            }

            is McuMeetEvent.ShowToastByErrorCode -> ToastUtil.toastErrorCode(event.errorCode) // 根据错误码显示提示
            is McuMeetEvent.ShowLeaveOrCloseDialog -> if (event.isHost) showLeaveDialogByHost() else showLeaveDialogByParticipant() // 显示离开或结束会议确认框
            is McuMeetEvent.FinishRoomAndJumpDuration -> finishAndJumpStatistics() // 结束会议并跳转到时长统计页面
        }
    }

    /**
     * 结束当前会议 Activity 并跳转到会议时长统计页面。
     */
    private fun finishAndJumpStatistics() {
        logD("结束会议并跳转到时长统计页面")
        finish() // 关闭当前 Activity
        val quitSystemTimeMillis = System.currentTimeMillis() // 获取退出时间
        val state = viewModel.viewState.value // 获取当前 ViewModel 状态
        // 计算会议时长（秒）
        val duration = ((quitSystemTimeMillis - joinSystemTimeMillis) / 1000).toInt()
        // 使用 ARouter 跳转到时长统计页面，并传递必要参数
        ARouter.getInstance().build(RouterUrlManager.MEETING_DURATION_STATISTICS_ACTIVITY)
            .withLong(
                IntentValues.MEETING_CREATE_TIMES,
                state.meetingInfo?.startTimeMs ?: 0
            ) // 会议创建时间
            .withString(
                IntentValues.MEETING_DURATION,
                TimeUtil.secondToHHmmss(duration)
            ) // 会议时长（格式化）
            .withString(IntentValues.MEETING_HOST_NAME, state.meetingInfo?.hostInfo?.name) // 主持人名称
            .withString(IntentValues.MEETING_SUBJECT, state.meetingInfo?.subject) // 会议主题
            .withInt(IntentValues.MEETING_ROOM_CLOSE_REASON, state.roomCloseReason) // 会议关闭原因
            .withInt(
                IntentValues.MEETING_ROOM_MY_ROLE,
                state.role?.ordinal ?: QRoomRole.PARTICIPANT.ordinal
            ) // 本人角色
            .withInt(
                IntentValues.ROOM_ID_TYPE,
                state.meetingInfo?.room?.type ?: EnRoomIdType.ROOMID_PERSONAL
            ) // 房间 ID 类型
            .withTransition( // 设置转场动画
                com.huidiandian.meeting.base.R.anim.fade_in,
                com.huidiandian.meeting.base.R.anim.fade_out
            )
            .navigation(this) // 执行跳转
    }

    /**
     * 显示主持人离开会议时的确认对话框 (结束会议 vs 离开会议)。
     */
    private fun showLeaveDialogByHost() {
        logD("显示主持人离开/结束会议对话框")
        val fragment = ExitMeetingDialog(object : ExitMeetingDialog.ExitMeetingDialogListener {
            /** 点击 "结束会议" */
            override fun onExitMeeting() {
                logD("主持人选择结束会议")
                viewModel.closeRoom() // 调用 ViewModel 关闭房间
            }

            /** 点击 "离开会议" */
            override fun onLeaveMeeting() {
                logD("主持人选择离开会议")
                viewModel.leaveRoom() // 调用 ViewModel 离开房间
            }
        })
        fragment.show(supportFragmentManager, TAG) // 显示 DialogFragment
    }

    /**
     * 显示普通参会者离开会议时的确认对话框。
     */
    private fun showLeaveDialogByParticipant() {
        logD("显示参会者离开会议对话框")
        DialogUtils.showCommonDoubleBtnNewStyleDialog(
            this,
            this.getString(R.string.meeting_str_sure_to_leave_meeting), // 提示文本
            this.getString(R.string.str_cancel), // 取消按钮文本
            null, // 取消按钮回调 (null)
            this.getString(com.huidiandian.meeting.base.R.string.str_sure), // 确认按钮文本
            { // 确认按钮回调
                logD("参会者确认离开会议")
                viewModel.leaveRoom() // 调用 ViewModel 离开房间
            },
            false, // 是否可取消 (false)
            null // Dialog 关闭回调 (null)
        )
    }

    /**
     * 显示被主持人禁麦时的提示对话框，提供 "申请发言" 选项。
     */
    private fun showMicBanDialog() {
        logD("显示被主持人禁麦对话框")
        // 加载自定义对话框布局
        val hostBanLoudSpeakerView =
            LayoutInflater.from(this).inflate(R.layout.dialog_host_ban_mic, null)
        // 计算对话框尺寸
        val width: Int = ScreenUtils.dp2px(270f)
        val height: Int = ScreenUtils.dp2px(140f)
        // 创建通用对话框
        val hostBanMicDialog = DialogHelper.getCommonDialog(
            this,
            hostBanLoudSpeakerView,
            Gravity.CENTER, // 居中显示
            R.style.center_alpha_scale_animation, // 动画样式
            width, height,
            0.8f // 背景透明度
        )
        // 获取布局中的按钮
        val tvCancelApplyDialog =
            hostBanLoudSpeakerView.findViewById<TextView>(R.id.tv_cancel_apply_dialog) // "知道了" 按钮
        val tvApplyToOpenSpeaker =
            hostBanLoudSpeakerView.findViewById<TextView>(R.id.tv_apply_to_open_speaker) // "申请发言" 按钮

        // 设置 "知道了" 按钮点击事件
        tvCancelApplyDialog?.setOnClickListener {
            hostBanMicDialog.dismiss() // 关闭对话框
        }
        // 设置 "申请发言" 按钮点击事件
        tvApplyToOpenSpeaker?.setOnClickListener {
            logD("用户申请开启麦克风")
            viewModel.applyOpenMic() // 调用 ViewModel 发起申请
            hostBanMicDialog.dismiss() // 关闭对话框
        }
        // 显示对话框
        hostBanMicDialog.show()
    }

    /**
     * 设置 ViewModel 状态 ([McuMeetState]) 的观察者。
     * 使用 [observeState] 辅助函数，结合 `flowWithLifecycle` 来安全地观察 Flow。
     */
    private fun setupObservers() {
        logD("设置 ViewModel 状态观察者")

        // 观察用户信息变化
        observeState({ it.userInfo }) { userInfoState ->
            logD("观察到用户信息变化")
            binding.meetMenuView.updateUserName(userInfoState?.userInfo?.name)
            localFloatViewManager?.updateUserInfo(
                userInfoState?.userInfo?.name,
                userInfoState?.userInfo?.icon
            )
        }
        // 观察会议信息变化
        observeState({ it.meetingInfo }) { meetingInfo ->
            logD("观察到会议信息变化")
            meetingInfo?.let { roomInfo -> binding.meetMenuView.setRoomInfo(roomInfo) }
        }
        // 观察录制状态变化
        observeState({ it.recordingState }) { recordingState ->
            logD("观察到录制状态变化: $recordingState")
            updateRecordState(recordingState)
        }
        // 观察麦克风启用状态变化
        observeState({ it.isMicEnabled }) { isMicEnabled ->
            logD("观察到麦克风状态变化: $isMicEnabled")
            binding.meetMenuView.updateMicState(isMicEnabled)
            localFloatViewManager?.updateMicState(isMicEnabled)
            if (isMicEnabled) viewModel.enableMicrophone()
        }
        // 观察是否处于语音模式变化
        observeState({ it.isVoiceMode }) { isVoiceMode ->
            logD("观察到语音模式状态变化: $isVoiceMode")
            updateVoiceMode(isVoiceMode) // 更新整体 UI
            binding.meetMenuView.updateVoiceModeState(isVoiceMode) // 更新菜单栏图标状态
        }
        // 观察摄像头启用状态变化
        observeState({ it.isCameraEnabled }) { isCameraEnabled ->
            logD("观察到摄像头状态变化: $isCameraEnabled")
            binding.meetMenuView.updateCameraState(isCameraEnabled)
            localFloatViewManager?.updateCameraState(isCameraEnabled)
        }
        // 观察屏幕共享状态变化 (组合状态，包含是否有人共享、是否自己共享、共享者账号)
        observeState({
            SharingState(
                it.isSelfSharing,
                it.shareAccount,
                it.isSharing
            )
        }) { sharingState ->
            logD("观察到共享状态变化: $sharingState")
            updateSharingState(sharingState) // 更新共享相关 UI
        }
        // 观察本地预览悬浮窗显示状态变化
        observeState({ it.isPreview }) { isPreview ->
            logD("观察到预览浮窗状态变化: $isPreview")
            localFloatViewManager?.updatePreviewState(isPreview) // 更新悬浮窗显示状态
        }
        // 观察本人是否为主持人状态变化
        observeState({ it.isHost }) { isHost ->
            logD("观察到主持人状态变化: $isHost")
            binding.meetMenuView.updateIsHost(isHost) // 更新菜单栏（可能影响显示的操作按钮）
        }

        // 观察本地麦克风音量变化 (假设 localMicVolume 是 Flow)
        lifecycleScope.launch {
            viewModel.localMicVolume
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED) // 绑定生命周期
                .collect { volume ->
                    // logD("观察到本地麦克风音量变化: $volume") // 日志可能过于频繁，按需开启
                    binding.meetMenuView.updateMicVolume(volume) // 更新菜单栏麦克风音量指示
                    localFloatViewManager?.updateMicVolume(volume) // 更新悬浮窗麦克风音量指示
                }
        }

        // --- LiveEventBus 事件监听 ---
        // 监听请求开始屏幕共享的事件
        LiveEventBus.get(LiveDataBusConstantBaseModule.SHARE_SCREEN, ShareScreen::class.java)
            .observe(this) {
                logD("收到 LiveEventBus 事件: SHARE_SCREEN")
                viewModel.startScreenShare() // 调用 ViewModel 开始屏幕共享流程
            }
        // 监听请求隐藏参会者列表的事件
        LiveEventBus.get(HIDE_LIST, HideUserListFragment::class.java).observe(this) {
            logD("收到 LiveEventBus 事件: HIDE_LIST")
            hideParticipantsFragment() // 隐藏参会者列表
        }
    }

    /**
     * 更新菜单栏上的录制状态指示器。
     * @param state 录制状态对象。
     */
    private fun updateRecordState(state: RecordState) =
        binding.meetMenuView.updateRecordState(state.isRecording, state.reason, state.startTime)

    /**
     * 根据是否处于语音模式更新整体 UI 布局。
     * 语音模式下隐藏 ViewPager，显示头像和名称；非语音模式则相反。
     * @param isVoiceMode 是否处于语音模式。
     */
    private fun updateVoiceMode(isVoiceMode: Boolean) = with(binding) {
        if (isVoiceMode) {
            // --- 进入语音模式 ---
            logD("更新 UI 为语音模式")
            meetMenuView.binding.menuCamera.alpha = 0.2f // 摄像头按钮置灰
            meetMenuView.binding.changeCamera.visibility = GONE // 隐藏切换摄像头按钮
            meetMenuView.binding.menuShare.alpha = 0.2f // 共享按钮置灰
            viewPager.visibility = GONE // 隐藏视频区域 ViewPager
            constraintVoiceMode.visibility = VISIBLE // 显示语音模式下的头像和名称区域
            // 设置头像和名称
            viewModel.viewState.value.userInfo?.userInfo?.let {
                tvName.text = it.name
                CircleTextImageViewUtil.setViewData(
                    MiguVideoApplicaition.getContext(),
                    ivVoiceAvatar, it.icon, it.name, it.accountType,
                    com.huidiandian.meeting.base.R.drawable.icon_avatar_hard_terminal_dark, // 默认头像
                    true
                )
            }
        } else {
            // --- 退出语音模式 ---
            logD("更新 UI 为视频模式")
            meetMenuView.binding.menuCamera.alpha = 1f // 恢复摄像头按钮
            meetMenuView.binding.menuShare.alpha = 1f // 恢复共享按钮
            meetMenuView.binding.changeCamera.visibility = VISIBLE // 显示切换摄像头按钮
            viewPager.visibility = VISIBLE // 显示视频区域 ViewPager
            constraintVoiceMode.visibility = GONE // 隐藏语音模式区域
        }
    }

    /**
     * 根据屏幕共享状态更新相关 UI。
     * 包括菜单栏共享按钮状态、共享控制层显隐、ViewPager 内容。
     * @param state 共享状态对象。
     */
    private fun updateSharingState(state: SharingState) {
        binding.meetMenuView.updateSharingState(state.isSharing) // 更新菜单栏共享按钮状态
        // 如果是自己正在共享，显示顶部的停止共享控制条
        binding.layoutShareLayer.root.visibility =
            if (state.isSelfSharing) VISIBLE else GONE
        handleSharingViewState(state) // 处理 ViewPager 中的共享画面显示/移除
    }

    /**
     * 处理 ViewPager 中屏幕共享画面的添加与移除。
     * @param state 共享状态对象。
     */
    private fun handleSharingViewState(state: SharingState) {
        if (state.isSharing && !state.isSelfSharing) {
            // --- 他人开始共享 ---
            logD("他人 (${state.shareAccount}) 开始共享，处理 ViewPager")
            // 如果当前 ViewPager 只有主会场 Fragment，则添加共享画面 Fragment
            if (pagerAdapter.itemCount == 1) {
                logD("ViewPager 中添加共享画面 Fragment")
                pagerAdapter.addFragment(
                    ScreenShareFragment.newInstance(
                        state.shareAccount, QStreamType.SHARE.ordinal
                    )
                )
            }
            // 如果当前 Activity 不处于某个用户的全屏模式，则自动切换到共享画面
            if (!isInFullScreenMode()) {
                logD("非全屏模式，自动切换到共享画面 (ViewPager 第二页)")
                binding.viewPager.currentItem = 1
            } else {
                logD("全屏模式，不自动切换 ViewPager")
            }
        } else {
            // --- 共享结束或自己开始共享 ---
            logD("共享结束或自己开始共享，处理 ViewPager")
            // 如果 ViewPager 中包含共享画面 Fragment (即 itemCount > 1)，则移除它
            if (pagerAdapter.itemCount > 1) {
                logD("ViewPager 中移除共享画面 Fragment")
                pagerAdapter.removeFragment(1) // 移除第二个 Fragment (共享画面)
                binding.viewPager.currentItem = 0 // 切换回主会场
            }
        }
    }

    /**
     * 创建底部菜单栏主按钮的点击监听器。
     * @return MeetMenuView.OnMenuItemClickListener 实例。
     */
    private fun createMenuClickListener() = object : MeetMenuView.OnMenuItemClickListener {
        /** 点击麦克风按钮 */
        override fun onMicClick() {
            logD("菜单 - 点击麦克风")
            viewModel.toggleMicrophone()
        }

        /** 点击摄像头按钮 */
        override fun onCameraClick() {
            logD("菜单 - 点击摄像头")
            viewModel.toggleCamera()
        }

        /** 点击共享按钮 */
        override fun onShareClick() {
            logD("菜单 - 点击共享")
            viewModel.toggleSharing() // ViewModel 会处理开始或停止共享的逻辑
        }

        /** 点击参会者按钮 */
        override fun onParticipantsClick() {
            logD("菜单 - 点击参会者")
            showMeetingHost() // 显示参会者列表
        }

        /** 点击关闭/挂断按钮 */
        override fun onCloseClick() {
            logD("菜单 - 点击关闭/挂断")
            viewModel.leaveOrCloseRoom() // ViewModel 会根据角色判断是离开还是结束
        }

        /** 点击会议信息按钮 (通常是标题区域) */
        override fun onMeetInfoClick(view: View) {
            logD("菜单 - 点击会议信息")
            showMeetingInfoDialog(view) // 显示会议信息弹窗
        }

        /** 点击切换摄像头按钮 */
        override fun onSwitchCameraClick() {
            logD("菜单 - 点击切换摄像头")
            viewModel.switchCamera()
        }

        /** 点击网络状态图标 */
        override fun onNetIntensityClick() {
            logD("菜单 - 点击网络状态")
            showNetAndStreamStatePop() // 显示网络和流状态详情弹窗
        }

        /** 点击语音模式按钮 (此回调在当前逻辑下可能未使用，由 onAudioModeClick 处理) */
        override fun onVoiceModeClick() {
            logD("菜单 - 点击语音模式 (未使用)")
        }
    }

    /**
     * 显示发起屏幕共享的选项弹窗 (例如：共享屏幕、共享白板等)。
     */
    private fun showSharePop() {
        logD("显示共享选项弹窗")
        val fragment = ShareScreenFragment() // 创建共享选项 DialogFragment
        fragment.show(supportFragmentManager, TAG) // 显示
    }


    /**
     * 显示参会者列表 Fragment ([MeetHostFragment])。
     * 如果 Fragment 未创建则创建并添加，已创建则显示。
     * 执行从右侧滑入的动画。
     */
    private fun showMeetingHost() {
        logD("显示参会者列表")
        val fm = supportFragmentManager
        val begin = fm.beginTransaction()
        if (meetHostFragment == null) {
            logD("首次显示，创建 MeetHostFragment")
            meetHostFragment = MeetHostFragment()
            // 准备传递给 Fragment 的参数
            val b = Bundle()
            b.putBoolean(IntentValues.IS_HOST, viewModel.viewState.value.isHost) // 传递是否为主持人
            b.putString(IntentValues.ROOM_ID, viewModel.viewState.value.roomId) // 传递房间 ID
            meetHostFragment?.arguments = b
            // 添加 Fragment 到容器
            begin.add(R.id.fl_meet_host, meetHostFragment!!)
        } else {
            logD("非首次显示，直接 show MeetHostFragment")
            begin.show(meetHostFragment!!) // 显示已存在的 Fragment
        }
        begin.commitAllowingStateLoss() // 提交事务

        // 延迟一小段时间后刷新 Fragment 视图 (可能为了等待 Fragment 状态恢复)
        lifecycleScope.launch {
            delay(300)
            withContext(Dispatchers.Main) {
                meetHostFragment?.refreshView()
            }
        }
        // 显示 Fragment 容器，并启动滑入动画
        binding.constraintHost.visibility = VISIBLE
        binding.flMeetHost.startAnimation(getSwitchScreenAnimation(true))
    }

    /**
     * 获取用于显示/隐藏参会者列表的平移动画。
     * @param show true 表示获取滑入动画，false 表示获取滑出动画。
     * @return TranslateAnimation 实例。
     */
    private fun getSwitchScreenAnimation(show: Boolean): TranslateAnimation {
        val mShowAction: TranslateAnimation
        if (show) {
            // --- 滑入动画 (从右到左) ---
            mShowAction = TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 1.0f, // 起始 X: 自身宽度的 100% (右侧屏幕外)
                Animation.RELATIVE_TO_SELF, 0.0f, // 结束 X: 自身宽度的 0% (原始位置)
                Animation.RELATIVE_TO_SELF, 0.0f, // 起始/结束 Y: 不变
                Animation.RELATIVE_TO_SELF, 0.0f
            )
            mShowAction.repeatMode = Animation.REVERSE // 重复模式（虽然这里 duration 较短，可能无影响）
            mShowAction.duration = 200 // 动画时长
        } else {
            // --- 滑出动画 (从左到右) ---
            mShowAction = TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0.0f, // 起始 X: 原始位置
                Animation.RELATIVE_TO_SELF, 1.0f, // 结束 X: 右侧屏幕外
                Animation.RELATIVE_TO_SELF, 0.0f, // 起始/结束 Y: 不变
                Animation.RELATIVE_TO_SELF, 0.0f
            )
            // 设置动画监听，在滑出动画结束后隐藏容器 View
            mShowAction.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    binding.constraintHost.visibility = GONE // 隐藏容器
                }

                override fun onAnimationStart(animation: Animation?) {}
            })
            mShowAction.duration = 200 // 动画时长
        }
        return mShowAction
    }

    /**
     * 请求指定的权限。
     * 根据权限类型分发到不同的请求方法。
     * @param permission 需要请求的权限字符串 (来自 Manifest.permission)。
     */
    private fun requestPermission(permission: String) {
        logD("请求权限: $permission")
        when (permission) {
            Manifest.permission.SYSTEM_ALERT_WINDOW -> requestOverlayPermission() // 请求悬浮窗权限
            Manifest.permission.CAMERA -> requestCameraPermission() // 请求相机权限
            else -> requestGeneralPermission(permission) // 请求其他普通或危险权限
        }
    }

    /**
     * 跳转到系统设置页面请求悬浮窗权限 (SYSTEM_ALERT_WINDOW)。
     * 需要在 [onActivityResult] 中处理结果。
     */
    private fun requestOverlayPermission() {
        logD("跳转请求悬浮窗权限")
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = "package:${packageName}".toUri() // 指定包名
        }
        startActivityForResult(intent, CODE_REQUEST_OVERLAY) // 启动设置页，并带上请求码
    }

    /**
     * 使用自定义权限管理类请求相机权限。
     */
    private fun requestCameraPermission() {
        logD("请求相机权限")
        PermissionManager.with(this) // 使用权限管理器
            .permission(Manifest.permission.CAMERA, object : OnHHDPermissionCallback {
                /** 权限被授予时的回调 */
                override fun onGranted(permissions: List<String>, all: Boolean) {
                    if (all) {
                        logD("相机权限已获取")
                        viewModel.startPreview() // 权限获取成功后，开始本地预览
                    } else {
                        logD("相机权限部分获取或未获取")
                    }
                }
                // onDenied, onDeniedForever 等回调按需实现
            })
    }

    /**
     * 使用自定义权限管理类请求通用权限 (除悬浮窗、相机外)。
     * @param permission 需要请求的权限字符串。
     */
    private fun requestGeneralPermission(permission: String) {
        logD("请求通用权限: $permission")
        PermissionManager.with(this).permission(permission, object : OnHHDPermissionCallback {
            /** 权限被授予时的回调 */
            override fun onGranted(permissions: List<String>, all: Boolean) {
                logD("通用权限 $permissions 获取结果: $all")
                // 根据具体权限执行后续操作，例如获取录音权限后可以开启麦克风等
            }
            // onDenied, onDeniedForever 等回调按需实现
        })
    }

    /**
     * 设置 LiveEventBus 监听，用于接收请求屏幕捕捉权限的事件。
     * 这个事件通常由 ViewModel 在需要屏幕捕捉但权限不足时发送。
     */
    private fun setupScreenCapturePermissionListener() {
        logD("设置屏幕捕获权限请求监听")
        LiveEventBus.get<Unit>(LiveDataBusConstantBaseModule.SCREEN_CAPTURE_PERMISSION_REQUIRED)
            .observe(this) {
                logD("收到请求屏幕捕获权限的事件")
                requestScreenCapturePermission() // 弹出权限说明对话框
            }
    }

    /**
     * 显示请求屏幕捕捉权限（实际是悬浮窗权限）的说明对话框。
     * 用户点击确认后会跳转到系统设置页面。
     */
    private fun requestScreenCapturePermission() {
        logD("显示请求屏幕捕获(悬浮窗)权限说明对话框")
        val dialog = AlertPermissionDialog() // 创建权限说明对话框
        // 设置对话框确认按钮的回调
        dialog.rightBlock = {
            requestOverlayPermission() // 跳转请求悬浮窗权限
        }
        dialog.show(supportFragmentManager, "screenCapturePermission") // 显示对话框
    }

    /**
     * 判断当前是否处于某个用户的全屏模式。
     * @return 如果处于全屏模式则返回 true，否则返回 false。
     */
    private fun isInFullScreenMode(): Boolean {
        return getCurrentMainFragment()?.getMcuLayoutView()?.isInFullScreen() == true
    }

    /**
     * 获取当前 ViewPager 中显示的 McuMainFragment 实例。
     * @return 当前的 McuMainFragment 实例，如果适配器未初始化、索引越界或当前 Fragment 不是 McuMainFragment，则返回 null。
     */
    private fun getCurrentMainFragment(): McuMainFragment? {
        // 检查适配器是否已初始化以及当前索引是否有效
        if (!::pagerAdapter.isInitialized || binding.viewPager.currentItem >= pagerAdapter.fragments.size) {
            logD("获取当前 McuMainFragment 失败：适配器未初始化或索引越界")
            return null
        }
        // 获取当前位置的 Fragment
        val currentFragment = pagerAdapter.fragments[binding.viewPager.currentItem]
        // 尝试转换为 McuMainFragment 并返回，如果类型不匹配则返回 null
        return currentFragment as? McuMainFragment
    }

    /**
     * 设置 ViewPager2。
     * 创建适配器，添加初始的 Fragment (主会场)，并配置 ViewPager 属性。
     */
    private fun setupViewPager() {
        logD("设置 ViewPager")
        // 创建适配器并添加主会场 Fragment
        pagerAdapter =
            MeetingPagerAdapter(this).apply { addFragment(McuMainFragment.newInstance()) }
        binding.viewPager.apply {
            adapter = pagerAdapter // 设置适配器
            isUserInputEnabled = true // 允许用户手动滑动切换页面
            // 注册页面切换回调 (这里为空实现，可以按需添加逻辑)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    logD("ViewPager 切换到页面: $position")
                }
            })
            // 禁用 ViewPager2 默认的边缘滚动效果 (Overscroll)，优化视觉体验
            getChildAt(0)?.overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    /**
     * 处理从其他 Activity 返回的结果，特别是权限请求的结果。
     * @param requestCode 请求码。
     * @param resultCode 结果码 (RESULT_OK 或 RESULT_CANCELED)。
     * @param data 返回的 Intent 数据。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        logD("收到 onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        when (requestCode) {
            // 处理悬浮窗权限请求结果
            CODE_REQUEST_OVERLAY -> handleOverlayPermissionResult()
            // 处理屏幕捕捉权限请求结果 (MediaProjection)
            CODE_REQUEST_CAPTURE -> handleScreenCaptureResult(resultCode, data)
        }
    }

    /**
     * 处理悬浮窗权限请求返回的结果。
     * 检查权限是否已获取，若获取则继续屏幕共享流程，否则提示用户。
     */
    private fun handleOverlayPermissionResult() {
        // 再次检查悬浮窗权限
        if (viewModel.checkScreenCapturePermission()) { // ViewModel 中封装了检查逻辑
            logD("悬浮窗权限已在设置页获取")
            viewModel.continueScreenSharing() // 通知 ViewModel 继续屏幕共享流程
        } else {
            logD("悬浮窗权限仍未获取")
            ToastUtil.toast("需要悬浮窗权限才能进行屏幕共享") // 提示用户
        }
    }

    /**
     * 处理屏幕捕捉权限请求 (MediaProjection API) 返回的结果。
     * @param resultCode 结果码。
     * @param data 包含 MediaProjection 信息的 Intent。
     */
    private fun handleScreenCaptureResult(resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK && data != null) { // 用户同意屏幕捕捉
            logD("屏幕捕捉权限已获取 (MediaProjection)")
            // 启动协程执行后续的共享逻辑 (涉及网络和 Service 绑定)
            lifecycleScope.launch {
                // 调用 ViewModel 的方法切换共享状态（内部会更新 UI 并调用 RTC 引擎）
                viewModel.startSharing().await().onSuccess {
                    logD("ViewModel 切换共享状态成功")
                    // 设置 Service 启动时的回调，用于传递 MediaProjection 数据
                    setScreenServiceOnStartCommandListener(data)
                    // 绑定屏幕共享 Service
                    bindScreenShareService(resultCode, data)
                }.onError { error, _ ->
                    logD("ViewModel 切换共享状态失败: ${error.message}")
                    ToastUtil.toast("开启屏幕共享失败") // 提示用户
                }
            }
        } else { // 用户拒绝或取消
            logD("屏幕捕捉权限未获取 (MediaProjection)")
            ToastUtil.toast("需要屏幕捕获权限才能进行屏幕共享") // 提示用户
        }
    }

    /**
     * 设置屏幕共享 Service ([QScreenSharingService]) 的静态回调通道。
     * 当 Service 启动时，会通过此通道将 Binder 和 MediaProjection 数据传递回来。
     * @param data 包含 MediaProjection 信息的 Intent。
     */
    private fun setScreenServiceOnStartCommandListener(data: Intent?) {
        logD("设置屏幕共享 Service 的启动回调通道")
        // ServiceChannel 是 QScreenSharingService 中的一个静态变量或伴生对象属性
        QScreenSharingService.serviceChannel = QScreenSharingService.QServiceChannel { binder ->
            logD("屏幕共享 Service 启动回调触发")
            // 将 Service 的监听器设置为 Activity 中的监听器实例
            binder.screenSharingListener = mScreenSharingListener
            // 准备传递给 RTC 引擎的参数
            startPublishParams = StartPublishParams().apply {
                this.data = data // 关键：设置 MediaProjection 数据
                streamType = QStreamType.SHARE // 流类型为共享
                audioState = QDeviceState.OPEN // 共享时通常也需要共享音频
                videoState = QDeviceState.OPEN // 共享视频（屏幕画面）
            }
            // 获取 MediaProjectionManager 系统服务
            val mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // 调用 RTC 引擎接口，使用 MediaProjection 开始屏幕共享推流
            logD("调用 RTC 引擎 startSharePublishByAndroid")
            QRtcEngine.get().startSharePublishByAndroid(startPublishParams, mediaProjectionManager)
        }
    }

    /**
     * 绑定屏幕共享 Service ([QScreenSharingService])。
     * 并根据 Android 版本启动 Service (前台或普通)。
     * @param resultCode 屏幕捕捉的结果码。
     * @param data 包含 MediaProjection 信息的 Intent。
     */
    private fun bindScreenShareService(resultCode: Int, data: Intent?) {
        logD("绑定并启动屏幕共享 Service")
        // 判断当前用户是否为主持人
        val isHost = QRtcEngine.get()
            .getParticipant(MMKVUtil.decodeString(MMKVConstant.LOGIN_ACCOUNT))?.info?.role == QRoomRole.HOST.ordinal

        // 创建启动 Service 的 Intent，并传递必要数据
        val service = Intent(this, QScreenSharingService::class.java).apply {
            putExtra("resultCode", resultCode) // 传递结果码
            putExtra("data", data) // 传递 MediaProjection Intent
            putExtra(IntentValues.MYROLE, isHost) // 传递用户角色
        }

        // 绑定 Service，使用 BIND_AUTO_CREATE 标志位
        bindService(service, mScreenSharingConnection, BIND_AUTO_CREATE)

        // 根据 Android 版本启动 Service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // Android 8.0 及以上需要启动前台 Service
            logD("启动前台 Service (Android O+)")
            startForegroundService(service)
        } else {
            logD("启动普通 Service (Android O 以前)")
            startService(service)
        }
    }

    /**
     * ServiceConnection 的实现，用于监听屏幕共享 Service 的连接状态。
     */
    private val mScreenSharingConnection = object : ServiceConnection {
        /** Service 断开连接时的回调 */
        override fun onServiceDisconnected(name: ComponentName?) {
            logD("屏幕共享 Service 断开连接")
            mScreenSharingBinder = null // 清空 Binder 引用
        }

        /** Service 连接成功时的回调 */
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            logD("屏幕共享 Service 连接成功")
            // 将 IBinder 转换为自定义的 Binder
            (service as? QScreenSharingBinder)?.let { binder ->
                mScreenSharingBinder = binder // 保存 Binder 引用
                // 将 Activity 中的监听器设置给 Service 的 Binder
                mScreenSharingBinder?.screenSharingListener = mScreenSharingListener
            }
        }
    }

    /**
     * 屏幕共享 Service 的监听器实现 ([QIScreenSharingListener])。
     * 使用弱引用持有 Activity，防止内存泄漏。
     */
    private val mScreenSharingListener = object : QIScreenSharingListener {
        // 使用弱引用持有 Activity
        private val weakActivity = WeakReference(this@MCUMeetActivity)

        /** 当用户在 Service 的悬浮窗中点击 "返回应用" 时的回调 */
        override fun returnApp() {
            logD("屏幕共享监听器 - 请求返回应用")
            weakActivity.get()?.returnToApp() // 调用 Activity 的方法返回应用
        }

        /** 当用户在 Service 的悬浮窗中点击 "停止共享" 时的回调 */
        override fun stopSharing() {
            logD("屏幕共享监听器 - 请求停止共享")
            // 调用 ViewModel 处理停止共享的逻辑
            weakActivity.get()?.returnToApp() // 调用 Activity 的方法返回应用
            weakActivity.get()?.viewModel?.toggleSharing()
        }
    }

    /**
     * 将当前应用的任务带到前台，或重新启动 Activity。
     * 用于从屏幕共享 Service 的悬浮窗返回应用。
     */
    private fun returnToApp() {
        logD("执行返回应用逻辑, isFinishing=$isFinishing")
        if (isFinishing) return // 如果 Activity 正在销毁，则不执行

        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            // 尝试将当前任务移到前台
            if (taskId > 0) { // taskId 是 Activity 的属性
                logD("移动任务到前台 taskId=$taskId")
                am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
            } else { // 如果 taskId 无效，则尝试重新启动 Activity
                logD("taskId 无效，启动新 Intent")
                val intent = Intent(applicationContext, MCUMeetActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP) // 清除栈顶，复用实例
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            logD("返回应用时发生异常: ${e.message}")
        }
    }

    /**
     * Activity onStart 生命周期回调。
     */
    override fun onStart() {
        super.onStart()
        logD("onStart")
        // 如果当前正在共享屏幕（表示之前可能切到了后台），则隐藏 Service 创建的悬浮窗
        if (viewModel.viewState.value.isSelfSharing) {
            logD("正在共享，隐藏 Service 的悬浮窗")
            mScreenSharingBinder?.dismissFloatingWindow()
        }
    }

    /**
     * 处理返回按钮事件。
     * 通常触发离开或结束会议的确认流程。
     */
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        logD("onBackPressed - 触发离开/结束会议流程")
        viewModel.leaveOrCloseRoom()
        // 不调用 super.onBackPressed()，由 ViewModel 决定是否 finish
    }

    /**
     * Activity onPause 生命周期回调。
     */
    override fun onPause() {
        super.onPause()
        logD("onPause")
        // 如果当前正在共享屏幕，则显示 Service 创建的悬浮窗，以便用户在其他应用中控制共享
        if (viewModel.viewState.value.isSelfSharing) {
            logD("正在共享，显示 Service 的悬浮窗")
            mScreenSharingBinder?.showFloatingWindow(this)
        }
    }

    /**
     * Activity onDestroy 生命周期回调。
     * 在此释放所有持有的资源。
     */
    override fun onDestroy() {
        logD("onDestroy - 开始释放资源")
        safelyReleaseResources() // 安全地释放资源
        super.onDestroy()
        // 尝试释放当前 Fragment 的资源 (如果存在且实现了 release 方法)
        getCurrentMainFragment()?.release()
        logD("onDestroy - 资源释放完成")
    }

    /**
     * 安全地释放 Activity 持有的各种资源。
     * 包括悬浮窗、SurfaceView、手势处理器、Service 连接、菜单视图、屏幕常亮等。
     * 使用 try-catch 包裹以防止释放过程中出现异常导致 Crash。
     */
    private fun safelyReleaseResources() {
        localFloatViewManager?.release()
        try {
            logD("开始安全释放资源")
            // 销毁手势处理器
            if (::gestureHandler.isInitialized) {
                logD("销毁手势处理器")
                gestureHandler.destroy()
            }
            // 解绑屏幕共享服务
            if (mScreenSharingBinder != null) {
                logD("解绑屏幕共享服务")
                unbindService(mScreenSharingConnection)
                mScreenSharingBinder = null
            } else {
                logD("屏幕共享服务未绑定或已解绑")
            }

            // 释放菜单视图资源
            logD("释放菜单视图资源")
            binding.meetMenuView.release()

            // 恢复屏幕自动超时设置
            if (screenKeepAwakeManager.isKeepingAwake()) {
                logD("恢复屏幕超时设置")
                screenKeepAwakeManager.restoreScreenTimeout(this)
            }
            logD("安全释放资源完成")
        } catch (e: Exception) {
            logD("释放资源时发生异常: ${e.message}")
        }
    }

    /**
     * 显示会议信息弹窗 ([MeetingInfoPop])。
     * @param view 点击的视图，用于定位弹窗。
     */
    private fun showMeetingInfoDialog(view: View) {
        logD("显示会议信息弹窗")
        // 计算弹窗宽度，取屏幕宽高较小值
        val width: Int = min(ScreenUtils.screenHeight, ScreenUtils.screenWidth)
        val height: Int = width // 高度与宽度相同
        // 创建弹窗实例
        var meetingInfoPopup = MeetingInfoPop(this)
        // 获取当前会议信息和用户角色
        val roomInfo = viewModel.viewState.value.meetingInfo
        val role = viewModel.viewState.value.role
        // 设置弹窗宽高
        meetingInfoPopup.setWidth(width)
        meetingInfoPopup.setHeight(height)
        // 执行弹窗的创建逻辑 (可能是父类或接口方法)
        meetingInfoPopup = meetingInfoPopup.newCreate() as MeetingInfoPop
        // 设置弹窗动画样式
        meetingInfoPopup.setAnimStyle(com.huidiandian.meeting.base.R.style.meeting_info_style)
        // 设置 "复制邀请信息" 按钮的回调
        meetingInfoPopup.copyblock = {
            logD("会议信息弹窗 - 点击复制邀请信息")
            appendInviteInfoContent(resources.getString(R.string.str_copy_success)) // 拼接并复制信息
        }
        // 设置 "网络探测" 按钮的回调
        meetingInfoPopup.netAndStreamStateBlock = {
            logD("会议信息弹窗 - 点击网络探测")
            showNetAndStreamStatePop() // 显示网络状态弹窗
            meetingInfoPopup.dismiss() // 关闭会议信息弹窗
        }
        // 设置弹窗显示的数据
        meetingInfoPopup.setData(
            role == QRoomRole.HOST, // 是否为主持人
            roomInfo?.room?.id ?: "", // 房间号
            roomInfo?.password ?: "", // 会议密码 (可能为空)
            roomInfo?.hostInfo?.name ?: "", // 主持人名称
            roomInfo?.password ?: "", // 再次传递密码? (检查是否冗余)
            roomInfo?.subject ?: "" // 会议主题
        )
        // 在指定视图下方显示弹窗
        meetingInfoPopup.showBottom(view, 0, 0)
    }

    /**
     * 初始化网络及推拉流质量状态弹窗 ([NetAndStreamStatePop])。
     * 如果弹窗实例为 null，则创建新实例并设置回调。
     */
    private fun initNetAndStreamStatePop() {
        // 计算弹窗宽度
        val width: Int = ScreenUtils.dp2px(340f)
        if (netAndStreamStatePop == null) { // 仅在实例为空时创建
            logD("初始化网络状态弹窗")
            netAndStreamStatePop = NetAndStreamStatePop(
                MiguVideoApplicaition.getContext(), viewModel.viewState.value.roomId // 传入上下文和房间 ID
            )
            netAndStreamStatePop?.setWidth(width) // 设置宽度
            // 执行弹窗的创建逻辑
            netAndStreamStatePop = netAndStreamStatePop?.create() as NetAndStreamStatePop?
            // 设置信号更新回调 (当弹窗内部数据更新时触发)
            netAndStreamStatePop?.updateSignalBlock = {
                // 使用 IO 线程更新菜单栏的网络状态图标，避免阻塞主线程
                lifecycleScope.launch(Dispatchers.IO) {
                    binding.meetMenuView.updateMeetSignal(
                        netAndStreamStatePop?.serverDelay, // 服务器延迟
                        netAndStreamStatePop?.pushMyVideoStreamQuality, // 本地推流质量
                        netAndStreamStatePop?.badPlayerInfo?.values?.toList() // 拉流质量差的用户列表
                    )
                }
            }
            // 设置弹窗关闭回调
            netAndStreamStatePop?.dismissBlock = {
                logD("网络状态弹窗关闭")
                netAndStreamStatePop = null // 清空实例引用
                viewModel.setStreamStatsInterval(0) // 停止 RTC 引擎的统计信息回调（如果需要）
            }
            // 启动 RTC 引擎的统计信息回调 (例如每 2 秒回调一次)
            viewModel.setStreamStatsInterval(2)
        } else {
            logD("网络状态弹窗已初始化")
        }
    }

    /**
     * 显示网络及推拉流质量状态弹窗。
     * 会先调用 [initNetAndStreamStatePop] 确保实例存在。
     */
    private fun showNetAndStreamStatePop() {
        logD("显示网络状态弹窗")
        initNetAndStreamStatePop() // 确保弹窗已初始化
        // 设置当前用户是否为主持人 (可能影响弹窗显示内容)
        netAndStreamStatePop?.setIsHost(viewModel.viewState.value.role == QRoomRole.HOST)
        // 在屏幕中央显示弹窗
        netAndStreamStatePop?.showCenter(this.window.decorView)
    }

    /**
     * 拼接会议邀请信息文本，并复制到剪贴板。
     * @param toastMsg 复制成功后显示的 Toast 消息。
     */
    private fun appendInviteInfoContent(toastMsg: String) {
        logD("拼接并复制邀请信息")
        val stringBuilder = StringBuilder()
        // 获取当前会议和用户信息
        val roomInfo = viewModel.viewState.value.meetingInfo
        val userInfo = viewModel.viewState.value.userInfo?.userInfo
        // 拼接邀请信息
        stringBuilder.append(
            resources.getString(
                R.string.meeting_str_invite_person, userInfo?.name ?: "我" // 邀请人
            ) + "\n"
        )
        stringBuilder.append(
            resources.getString(
                R.string.meeting_str_suject, roomInfo?.subject ?: "会议" // 会议主题
            ) + "\n"
        )
        stringBuilder.append(
            resources.getString(
                R.string.meeting_str_createtims, // 会议时间
                TimeUtil.long2String(roomInfo?.startTimeMs ?: 0, TimeUtil.FORMAT_TYPE_8)
            ) + "\n"
        )
        stringBuilder.append(
            resources.getString(
                R.string.str_room_number_with,
                roomInfo?.room?.id ?: "N/A"
            ) + "\n" // 会议号
        )
        if (!TextUtils.isEmpty(roomInfo?.password)) { // 如果有会议密码
            stringBuilder.append(
                resources.getString(R.string.str_room_password, roomInfo?.password) + "\n" // 会议密码
            )
        }
        stringBuilder.append(resources.getString(R.string.meeting_str_url_prompt) + "\n") // 邀请链接提示
        stringBuilder.append(roomInfo?.inviteUrl ?: "") // 邀请链接
        // 调用工具类复制到剪贴板，并显示 Toast
        copy(stringBuilder.toString(), toastMsg)
    }

    /**
     * 使用 `flowWithLifecycle` 安全地观察 ViewModel 的 StateFlow 的一部分。
     * 此函数已移除 'inline' 关键字以尝试解决潜在的优化问题。
     *
     * @param T 观察的状态部分的类型。
     * @param mapDistinct 用于从 McuMeetState 映射并去重你关心的状态部分。
     * @param action 当映射后的状态发生变化时执行的操作。
     */
    private fun <T> observeState(
        mapDistinct: (McuMeetState) -> T, // 状态映射函数
        action: (T) -> Unit, // 状态处理函数
    ) {
        lifecycleScope.launch { // 在 Activity 的生命周期协程作用域内启动
            viewModel.viewState // 获取 ViewModel 的 StateFlow
                .map(mapDistinct) // 应用映射函数
                .distinctUntilChanged() // 仅当值变化时才发射
                .flowWithLifecycle(
                    lifecycle,
                    Lifecycle.State.STARTED
                ) // 将 Flow 绑定到 Activity 的 STARTED 状态
                .collect { stateValue -> // 收集发射的值
                    action(stateValue) // 执行处理操作
                }
        }
    }

    /**
     * 记录 Debug 级别的日志，自动添加 TAG 前缀。
     * @param message 日志消息。
     */
    private fun logD(message: String) {
        AppLogger.getInstance().d("$TAG: $message")
    }

}