package com.huidiandian.meeting.mcu.view

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleObserver
import cn.geedow.netprotocol.JNIOrgMemberInfo
import cn.geedow.netprotocol.basicDataStructure.JNICalleeInfo
import com.alibaba.android.arouter.launcher.ARouter
import com.bifrost.rtcengine.QRtcEngine
import com.bifrost.rtcengine.entity.QRoom
import com.bifrost.rtcengine.jnienum.QMeetingInfoOption
import com.bifrost.rtcengine.jnienum.QStopRecordReason
import com.huidiandian.meeting.base.application.MiguVideoApplicaition
import com.huidiandian.meeting.base.constant.RouterUrlManager
import com.huidiandian.meeting.base.entity.OrgInfo
import com.huidiandian.meeting.base.entity.QConstant
import com.huidiandian.meeting.base.util.AppLogger
import com.huidiandian.meeting.base.util.DialogUtils
import com.huidiandian.meeting.base.util.HDDUtils.copy
import com.huidiandian.meeting.base.util.ScreenUtils
import com.huidiandian.meeting.base.util.TimeUtil
import com.huidiandian.meeting.base.util.ToastUtil.toast
import com.huidiandian.meeting.base.webview.ConstantValues
import com.huidiandian.meeting.base.webview.IntentValues
import com.huidiandian.meeting.core.extension.setOnSingleClickListener
import com.huidiandian.meeting.meeting.R
import com.huidiandian.meeting.meeting.chat.OrientationProxyActivity
import com.huidiandian.meeting.meeting.databinding.LayoutMeetMenuViewBinding
import com.huidiandian.meeting.meeting.room.MeetingInvitePop
import com.huidiandian.meeting.meeting.room.MeetingRoomModeMorePop
import com.huidiandian.meeting.meeting.room.meethost.InviteCallListPopWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min

class MeetMenuView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = -1,
) : FrameLayout(context, attrs, defStyleAttr), LifecycleObserver {

    companion object {
        private const val TAG = "MeetMenuView"
        private const val HIDE_DELAY_MS = 3000L
        private const val RECORDING_ANIMATION_DURATION = 500L
    }

    // 视图绑定
    val binding: LayoutMeetMenuViewBinding =
        LayoutMeetMenuViewBinding.inflate(LayoutInflater.from(context), this, true)

    // 协程作用域
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 隐藏菜单定时
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var isHiding = false

    // 会议与用户状态
    private var isHost = false
    private var isVoiceMode = false
    private var isMeetingTimeStarted = false
    private var userName: String? = null
    private var roomInfo: QRoom? = null
    private val mCallList = arrayListOf<JNICalleeInfo>()

    // 弹窗与动画
    private var ivRecordingAnim: ObjectAnimator? = null
    private var morePop: MeetingRoomModeMorePop? = null
    private var meetingInvitePop: MeetingInvitePop? = null
    private var inviteCallListPopWindow: InviteCallListPopWindow? = null

    // 监听回调
    private var menuItemClickListener: OnMenuItemClickListener? = null
    private var onMoreItemClickListener: OnMoreItemClickListener? = null

    interface OnMenuItemClickListener {
        fun onMicClick()
        fun onCameraClick()
        fun onShareClick()
        fun onParticipantsClick()
        fun onCloseClick()
        fun onMeetInfoClick(view: View)
        fun onSwitchCameraClick()
        fun onVoiceModeClick()
    }

    interface OnMoreItemClickListener {
        fun onAudioModeClick(audio: Boolean)
    }

    init {
        initMenuEventListeners()
        resetHideTimer()
    }

    private fun initMenuEventListeners() = with(binding) {
        val clickActions = mapOf(
            menuMic to { menuItemClickListener?.onMicClick() },
            menuCamera to { menuItemClickListener?.onCameraClick() },
            menuShare to { menuItemClickListener?.onShareClick() },
            menuUsers to { menuItemClickListener?.onParticipantsClick() },
            menuMore to { showMoreDialog() },
            tvEndButton to { menuItemClickListener?.onCloseClick() },
            tvMeetNumber to { menuItemClickListener?.onMeetInfoClick(tvMeetNumber) },
            changeCamera to { menuItemClickListener?.onSwitchCameraClick() },
            ivVoice to { menuItemClickListener?.onVoiceModeClick() },
            layoutChat to { launchMeetChatActivity() },
        )
        clickActions.forEach { (view, action) ->
            view.setOnSingleClickListener { action(); resetHideTimer() }
        }
        root.setOnSingleClickListener { if (menuGroup.alpha == 1f) hide() }
    }

    private fun showMoreDialog() {
        if (morePop == null) {
            val id = roomInfo?.room?.id
            if (id.isNullOrEmpty()) {
                AppLogger.getInstance().e("房间信息为空，无法显示更多选项对话框")
                return
            }
            val size = min(ScreenUtils.screenHeight, ScreenUtils.screenWidth)
            morePop = MeetingRoomModeMorePop(context, isHost, binding.menuMore).apply {
                setStartRecordingParams(id, false)
                setOutsideTouchable(true)
                setFocusable(false)
                setWidth(size)
                setHeight(size)
                audioModeBlock = { audio -> onMoreItemClickListener?.onAudioModeClick(audio) }
                onStorageFullDialogListener =
                    object : MeetingRoomModeMorePop.OnClickContinueRecordListener {
                        override fun onStorageFullDialogClick() {
                            setRecordingStartTime(0)
                        }
                    }
                inviteBlock = { invite() }
                settingBlock = {
                    ARouter.getInstance().build(RouterUrlManager.MEETING_ROOM_SETTING_ACTIVITY)
                        .navigation()
                }
                imClickBlock = { launchMeetChatActivity() }
                newCreate()
            }
        }
        morePop?.apply {
            setAudioMode(isVoiceMode)
            showPopTop()
            initViewBackGround(false)
        }
    }

    private fun invite() {
        if (roomInfo?.meetingLock == true) {
            DialogUtils.showCommonDoubleBtnDialog(
                context, context.getString(R.string.meeting_str_lock_hint),
                context.getString(R.string.str_cancel), {},
                context.getString(R.string.meeting_str_lock_btn),
                {
                    viewScope.launch {
                        val room = QRtcEngine.get().meetingInfo.apply { meetingLock = false }
                        val code = QRtcEngine.get().setMeetingInfo(
                            QMeetingInfoOption.MEETINGINFO_OPTION_isLocked, room
                        )
                        if (code != 0) {
                            AppLogger.getInstance().e("解锁会议失败: $code")
                        } else {
                            toast(context.getString(R.string.meeting_str_lock_hint1))
                            invitePopShow()
                        }
                    }
                }, false, ""
            )
        } else {
            invitePopShow()
        }
    }

    private fun launchMeetChatActivity() {
        val intent = Intent(context, OrientationProxyActivity::class.java).apply {
            putExtra(IntentValues.ROOM_ID, roomInfo?.room?.id)
            putExtra(IntentValues.IS_VOICE_MODE, isVoiceMode)
            putExtra(IntentValues.SCREEN_ORIENTATION, "landscape")
            putExtra(IntentValues.ROOM_CHANNEL_ID, QRtcEngine.get().pullChannelId)
        }
        context.startActivity(intent)
    }

    private fun invitePopShow() {
        val size = min(ScreenUtils.screenHeight, ScreenUtils.screenWidth)
        meetingInvitePop = MeetingInvitePop(context, binding.menuMore).apply {
            setOutsideTouchable(true)
            setFocusable(false)
            setInnerTouchable(false)
            setWidth(size)
            setHeight(size)
            newCreate()
            addressBookBlock = { launchChooseMemberActivity() }
            copyInviteBlock =
                { appendInviteInfoContent(context.resources.getString(R.string.meeting_str_copy_sucess_prompt)) }
            sendSMSBlock =
                { appendInviteInfoContent(context.resources.getString(R.string.meeting_str_quick_send_sms_prompt)) }
            initViewBackground()
            showBottom(binding.menuMore, 0, 0)
        }
    }

    private fun launchChooseMemberActivity() {
        viewScope.launch {
            val infos = ArrayList<OrgInfo>()
            inviteCallListPopWindow?.refreshInviteData()
            mCallList.forEach {
                val member = JNIOrgMemberInfo().apply {
                    account = it.userInfo?.account
                    name = it.userInfo?.name
                }
                val org = OrgInfo().apply {
                    memberInfo = member
                    type = OrgInfo.INFO_TYPE_MEMBER
                    isSelected = true
                    callTimes = it.expireTime
                }
                infos.add(org)
            }
            val companyInfo = QRtcEngine.get().companyResourceInfo?.info
            ARouter.getInstance()
                .build(RouterUrlManager.CHOOSE_MEMBERS_ACTIVITY)
                .withInt(
                    IntentValues.SELECTED_MEMBERS_MAX_SIZE,
                    companyInfo?.randomRoomCapacity ?: 0
                )
                .withInt(
                    IntentValues.CHOOSE_MEMBER_FROM_TYPE,
                    ConstantValues.CHOOSE_MEMBER_TYPE_MEET
                )
                .withInt(IntentValues.CHOOSE_TYPE, QConstant.CHOOSE_TYPE_INVITE)
                .navigation()
        }
    }

    private fun appendInviteInfoContent(toastMsg: String) {
        val inviteInfo = buildString {
            append(resources.getString(R.string.meeting_str_invite_person, userName) + "\n")
            append(resources.getString(R.string.meeting_str_suject, roomInfo?.subject) + "\n")
            append(
                resources.getString(
                    R.string.meeting_str_createtims,
                    TimeUtil.long2String(roomInfo?.startTimeMs ?: 0, TimeUtil.FORMAT_TYPE_8)
                ) + "\n"
            )
            append(resources.getString(R.string.str_room_number_with, roomInfo?.room?.id) + "\n")
            if (!TextUtils.isEmpty(roomInfo?.password))
                append(resources.getString(R.string.str_room_password, roomInfo?.password) + "\n")
            append(context.resources.getString(R.string.meeting_str_url_prompt) + "\n")
            append(roomInfo?.inviteUrl)
        }
        copy(inviteInfo, toastMsg)
    }

    fun isPointInMenuArea(x: Float, y: Float): Boolean {
        if (!isMenuVisible()) return false
        val topMenuRect = Rect().also { binding.viewTopBackground.getGlobalVisibleRect(it) }
        val stateBarRect = Rect().also { binding.stateBarTopView.getGlobalVisibleRect(it) }
        if (!stateBarRect.isEmpty) topMenuRect.top = stateBarRect.top
        val bottomMenuRect = Rect().also { binding.bottomLayout.getGlobalVisibleRect(it) }
        val inTopMenu = topMenuRect.contains(x.toInt(), y.toInt())
        val inBottomMenu = bottomMenuRect.contains(x.toInt(), y.toInt())
        Log.d(TAG, "点击点($x, $y)是否在菜单区域: 顶部=$inTopMenu, 底部=$inBottomMenu")
        return inTopMenu || inBottomMenu
    }

    fun setOnMenuItemClickListener(listener: OnMenuItemClickListener) {
        menuItemClickListener = listener
    }

    fun setOnMoreItemClickListener(listener: OnMoreItemClickListener) {
        onMoreItemClickListener = listener
    }

    fun setRoomInfo(roomInfo: QRoom) {
        binding.tvMeetNumber.text = roomInfo.room.id
        this.roomInfo = roomInfo
        if (isMeetingTimeStarted) return
        val startTime = roomInfo.startTimeMs
        val curTime =
            SystemClock.elapsedRealtime() + MiguVideoApplicaition.getContext().serverTimeDifference
        val interval = if (startTime in 1 until curTime) curTime - startTime else 0
        binding.meetTime.apply {
            base = SystemClock.elapsedRealtime() - interval
            start()
            setOnChronometerTickListener { chronometer ->
                val elapsedMillis = SystemClock.elapsedRealtime() - chronometer.base
                chronometer.format = when {
                    elapsedMillis < 3600000L -> "00:%s"
                    elapsedMillis in 3600001..35999999 -> "0%s"
                    else -> "%s"
                }
            }
        }
        isMeetingTimeStarted = true
    }

    fun show() {
        Log.d(TAG, "显示菜单")
        hideHandler.removeCallbacks(hideRunnable)
        with(binding.menuGroup) {
            visibility = VISIBLE
            alpha = 1f
        }
        isHiding = false
        resetHideTimer()
    }

    fun hide() {
        if (!isMenuVisible()) return
        isHiding = true
        binding.menuGroup.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.menuGroup.visibility = GONE
                isHiding = false
            }
    }

    fun isMenuVisible(): Boolean = binding.menuGroup.isVisible

    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    fun setStateBarHeight(stateBarHeight: Int) {
        binding.stateBarTopView.layoutParams = binding.stateBarTopView.layoutParams.apply {
            height = stateBarHeight
        }
    }

    fun updateMicState(micEnabled: Boolean) = with(binding) {
        AppLogger.getInstance().d("更新麦克风状态: $micEnabled")
        if (micEnabled) {
            menuMic.setButtonImage(R.drawable.layer_icon_mic_opened)
            menuMic.setButtonText(context.getString(R.string.meeting_str_mute))
        } else {
            menuMic.setButtonImage(R.drawable.icon_mic_closed)
            menuMic.setButtonText(context.getString(R.string.meeting_str_allowed_to_listener))
        }
    }

    fun updateCameraState(cameraEnabled: Boolean) = with(binding.menuCamera) {
        AppLogger.getInstance().d("更新摄像头状态: $cameraEnabled")
        if (cameraEnabled) {
            setButtonImage(R.drawable.selector_camera_open_icon)
            setButtonText(context.getString(R.string.meeting_str_close_camera))
        } else {
            setButtonImage(R.drawable.selector_camera_close_icon)
            setButtonText(context.getString(R.string.meeting_str_open_camera))
        }
    }

    fun updateSharingState(sharing: Boolean) = with(binding.menuShare) {
        AppLogger.getInstance().d("更新分享状态: $sharing")
        if (sharing) {
            setButtonImage(R.drawable.icon_stop)
            setButtonText(context.getString(R.string.meeting_str_stop_share))
            setButtonTextColor(resources.getColor(com.huidiandian.meeting.base.R.color.color_FF5000))
        } else {
            setButtonImage(R.drawable.selector_share_close_icon)
            setButtonText(context.getString(R.string.str_share_screen))
            setButtonTextColor(resources.getColor(com.huidiandian.meeting.base.R.color.color_999999))
        }
    }

    fun updateMicVolume(volume: Int) {
        if (binding.menuMic.getButtonText() == context.getString(R.string.meeting_str_allowed_to_listener)) return
        binding.menuMic.setMicVolumeLevel(volume)
    }

    fun release() {
        binding.meetTime.stop()
        binding.tvRecordingTime.stop()
        ivRecordingAnim?.takeIf { it.isRunning }?.cancel()
        ivRecordingAnim = null
        viewScope.cancel()
        hideHandler.removeCallbacksAndMessages(null)
    }

    fun setRecordingStartTime(startTimes: Long) {
        binding.constraintRecording.visibility = VISIBLE
        val curTime =
            SystemClock.elapsedRealtime() + MiguVideoApplicaition.getContext().serverTimeDifference
        val interval = if (startTimes in 1 until curTime) curTime - startTimes else 0
        binding.tvRecordingTime.apply {
            base = SystemClock.elapsedRealtime() - interval
            start()
            setOnChronometerTickListener { chronometer ->
                val elapsedMillis = SystemClock.elapsedRealtime() - chronometer.base
                chronometer.format = when {
                    elapsedMillis < 3600000L -> "00:%s"
                    elapsedMillis in 3600001..35999999 -> "0%s"
                    else -> "%s"
                }
            }
        }
        ivRecordingAnim =
            ObjectAnimator.ofFloat(binding.ivRecordingDot, "alpha", 1f, 0f, 1f).apply {
                duration = RECORDING_ANIMATION_DURATION
                repeatMode = ObjectAnimator.RESTART
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        morePop?.setRecordingStatus(true)
    }

    fun stopRecording() {
        binding.constraintRecording.visibility = GONE
        binding.tvRecordingTime.stop()
        ivRecordingAnim?.cancel()
        ivRecordingAnim = null
        morePop?.setRecordingStatus(false)
    }

    fun updateIsHost(bool: Boolean) {
        isHost = bool
    }

    fun updateVoiceModeState(bool: Boolean) {
        isVoiceMode = bool
    }

    fun updateRecordState(state: Boolean, reason: Int, startTimes: Long) {
        if (!isHost) return
        if (state) {
            setRecordingStartTime(startTimes)
        } else {
            stopRecording()
            val message = when (reason) {
                QStopRecordReason.STOP_RECORD_REASON_LESS_THAN_10_PERCENT ->
                    resources.getString(R.string.str_capacity_will_be_full_tips)

                QStopRecordReason.STOP_RECORD_REASON_RUN_OUT ->
                    resources.getString(R.string.str_capacity_is_full_tips)

                QStopRecordReason.STOP_RECORD_REASON_SERVER_INTERNAL_ERROR ->
                    resources.getString(R.string.str_stop_record_reason_server_internal_error)

                else -> null
            }
            message?.let { toast(it) }
        }
    }

    /**
     * 更新成员数量
     * @param numbers 成员数量
     */
    fun showNumbers(numbers: Int) {
        binding.menuUsers.showMemberNumbers(numbers)
    }

    fun updateUserName(userName: String?) {
        this.userName = userName
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }
}