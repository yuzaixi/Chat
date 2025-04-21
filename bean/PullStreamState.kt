package com.huidiandian.meeting.mcu.bean

/**
 * 拉流状态枚举
 */
enum class PullStreamState {
    IDLE,           // 初始状态，未开始拉流
    CREATING,       // 创建通道中，正在创建RTC通道
    CHECKING,       // ICE协商中，通道正在协商连接
    CONNECTED,      // 通道已连接，准备订阅流
    SUBSCRIBING,    // 订阅流请求中
    STREAMING,      // 流媒体播放中，已成功订阅并接收流
    DISCONNECTED,   // 通道已断开连接
    FAILED,         // 通道连接失败或订阅失败
    CLOSED          // 通道已关闭
}