package com.huidiandian.meeting.mcu.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import java.util.ArrayDeque

/**
 * 音量处理工具类，用于处理麦克风音量数据
 * - 平滑处理音量变化
 * - 控制数据更新频率
 * - 处理异常值和规范化数据
 */
class VolumeProcessor(
    private val sampleRateMs: Long = 100,      // 采样率(毫秒)
    private val smoothingFactor: Float = 0.3f,  // 平滑因子(0-1)，越小越平滑
    private val historySize: Int = 5,           // 历史数据大小
) {
    // 音量历史记录
    private val volumeHistory = ArrayDeque<Int>(historySize)

    // 上一次处理后的音量值
    private var lastProcessedVolume: Int = 0

    /**
     * 处理原始音量值
     * @param rawVolume 原始音量值(0-100)
     * @return 处理后的音量值(0-100)
     */
    fun processVolume(rawVolume: Int): Int {
        // 1. 异常值处理
        val validRawVolume = when {
            rawVolume < 0 -> 0
            rawVolume > 100 -> 100
            else -> rawVolume
        }

        // 2. 添加到历史记录
        if (volumeHistory.size >= historySize) {
            volumeHistory.removeFirst()
        }
        volumeHistory.addLast(validRawVolume)

        // 3. 平均值计算(简单移动平均)
        val avgVolume = if (volumeHistory.isEmpty()) 0
        else volumeHistory.sum() / volumeHistory.size

        // 4. 指数平滑处理
        val smoothedVolume = (smoothingFactor * validRawVolume +
                (1 - smoothingFactor) * lastProcessedVolume).toInt()

        // 5. 更新最后处理的音量
        lastProcessedVolume = smoothedVolume

        // 6. 返回平滑处理后的音量值
        return smoothedVolume
    }

    /**
     * 转换Flow，应用采样和处理
     * @param inputFlow 输入的音量Flow
     * @return 处理后的音量Flow
     */
    fun applyProcessing(inputFlow: Flow<Int>): Flow<Int> = flow {
        inputFlow
            .sample(sampleRateMs)  // 采样，控制更新频率
            .onStart { emit(0) }    // 初始值
            .flowOn(Dispatchers.Default)  // 在后台线程处理
            .collect { rawVolume ->
                val processedVolume = processVolume(rawVolume)
                emit(processedVolume)
            }
    }

    /**
     * 重置处理器状态
     */
    fun reset() {
        volumeHistory.clear()
        lastProcessedVolume = 0
    }
}