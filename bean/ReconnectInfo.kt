package com.huidiandian.meeting.mcu.bean

/**
 * 重连信息数据类
 */
data class ReconnectInfo(
    var attemptCount: Int,        // 重试次数
    var lastAttemptTime: Long,    // 上次尝试时间
)
