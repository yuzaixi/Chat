package com.huidiandian.meeting.mcu.bean

/**
 * 推流状态枚举
 */
enum class PublishStreamState {
    IDLE,           // 初始状态
    CONNECTING,     // 连接中
    CONNECTED,      // 已连接
    DISCONNECTED,   // 断开连接
    FAILED,         // 失败（可重试）
}