package com.huidiandian.meeting.mcu.repository

import cn.geedow.netprotocol.basicDataStructure.JNIView
import com.bifrost.rtcengine.entity.QSurfaceView
import com.huidiandian.meeting.mcu.bean.StreamInfo
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MCU会议数据仓库接口
 */
interface IMcuMainRepository {
    /**
     * 布局变更事件流
     */
    val layoutChangedEvent: StateFlow<List<JNIView>>

    /**
     * MCU首帧事件流
     */
    val mcuFirstFrame: SharedFlow<StreamInfo>

    /**
     * SFU首帧事件流
     */
    val sfuFirstFrame: SharedFlow<StreamInfo>

    /**
     * 渲染本地预览
     * @param surfaceView 渲染视图
     * @return 是否成功渲染
     */
    fun renderLocalPreview(surfaceView: QSurfaceView): Boolean

    /**
     * 切换到全屏渲染
     * @param userId 用户ID
     * @param streamType 流类型
     * @param surfaceView 渲染视图
     * @return 是否成功渲染
     */
    suspend fun switchToFullScreenRender(
        userId: String,
        streamType: Int,
        surfaceView: QSurfaceView,
    ): Boolean

    /**
     * 渲染多屏幕
     * @param surfaceView 渲染视图
     * @return 是否成功渲染
     */
    suspend fun renderMcuScreen(surfaceView: QSurfaceView): Boolean

    /**
     * 释放资源
     */
    fun release()
    fun exitFullScreenStream(string: String?)
}