package com.huidiandian.meeting.mcu.bean

/**
 * Participant data for meeting sessions
 * Contains essential information about a meeting participant
 */
data class ParticipantData(
    val account: String,
    val name: String = "",
    val avatar: String = "",
    val role: Int = 0,
    val micStatus: Boolean = false,
    val cameraStatus: Boolean = false,
    val isLocal: Boolean = false,
    val isSpeaking: Boolean = false,
    val audioLevel: Int = 0,
    val isSharing: Boolean = false,
) {
    companion object {
        const val ROLE_ATTENDEE = 0
        const val ROLE_HOST = 1
        const val ROLE_CO_HOST = 2
    }
}