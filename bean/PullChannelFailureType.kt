package com.huidiandian.meeting.mcu.bean

enum class PullChannelFailureType {
    CREATE_FAILED,      // 通道创建失败
    CHANNEL_FAILED,     // 通道出现一般性错误
    DISCONNECTED,       // 通道断开
    ROOM_ID_MISSING,    // 房间ID缺失
    STREAM_ID_MISSING,  // 混流ID缺失
    SUBSCRIBE_FAILED,   // 订阅失败
    EXCEPTION,          // 未知异常
}