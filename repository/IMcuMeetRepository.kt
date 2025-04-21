package com.huidiandian.meeting.mcu.repository

import com.bifrost.rtcengine.constants.QStreamType
import com.bifrost.rtcengine.entity.QParticipant
import com.bifrost.rtcengine.entity.QRoom
import com.huidiandian.meeting.mcu.bean.McuMeetState
import com.huidiandian.meeting.mcu.bean.ParticipantData
import com.huidiandian.meeting.mcu.bean.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 会议室Repository接口
 */
interface IMcuMeetRepository {
    val state: StateFlow<McuMeetState>
    val localMicVolume: MutableStateFlow<Int>
    suspend fun toggleMicrophone(): Result<Boolean>
    suspend fun toggleCamera(): Result<Boolean>
    suspend fun startScreenSharing(): Result<Unit>
    fun captureScreen()
    suspend fun stopScreenSharing(): Result<Unit>
    suspend fun refreshParticipants(): Result<List<ParticipantData>>
    suspend fun setupStream(): Result<Unit>
    fun release()
    fun checkScreenCapturePermission(): Boolean
    fun startPreview(streamType: QStreamType): Result<Unit>
    fun stopPreview(streamType: QStreamType): Result<Unit>
    fun enableMicrophone(streamType: QStreamType, enabled: Boolean): Result<Unit>
    suspend fun getSelfInfo(): Result<QParticipant?>
    suspend fun getMeetingInfo(): QRoom
    fun applyOpenMic(): Result<Int>
    fun getPublishers(): ArrayList<QParticipant>?
    suspend fun switchCamera(): Result<Unit>
    fun leaveRoom()
    fun closeRoom()
    fun setStreamStatsInterval(num: Int)
    fun switchVoiceMode(enabled: Boolean)
}