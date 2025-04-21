package com.huidiandian.meeting.mcu.bean

import cn.geedow.netprotocol.basicDataStructure.JNIView
import com.bifrost.rtcengine.constants.QStreamType

data class McuLayoutRect(
    val userId: String,
    val x: Float, // 比例坐标
    val y: Float,
    val width: Float,
    val height: Float,
    val streamType: Int = QStreamType.MAIN.ordinal
) {
    // 从 JNIRect 转换的函数
    companion object {
        fun fromJNIRect(
            jniView: JNIView,
            videoWidth: Int,
            videoHeight: Int
        ): McuLayoutRect {
            return McuLayoutRect(
                userId = jniView.account,
                x = jniView.rect.x.toFloat() / videoWidth,
                y = jniView.rect.y.toFloat() / videoHeight,
                width = jniView.rect.width.toFloat() / videoWidth,
                height = jniView.rect.height.toFloat() / videoHeight,
                streamType = jniView.streamType
            )
        }
    }
}
