package com.huidiandian.meeting.mcu.bean

import com.bifrost.rtcengine.constants.QStreamType

data class StreamInfo(val account: String, val streamType: QStreamType) {
    override fun toString(): String {
        return "StreamInfo(account='$account', streamType=${streamType.name})"
    }
}