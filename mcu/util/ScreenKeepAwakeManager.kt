package com.huidiandian.meeting.mcu.util

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import com.huidiandian.meeting.base.util.AppLogger

/**
 * 屏幕常亮管理工具类
 * 用于控制会议过程中屏幕不会自动关闭
 */
class ScreenKeepAwakeManager {

    companion object {
        private const val TAG = "ScreenKeepAwakeManager"
    }

    private var originalTimeout = -1
    private var isKeepingAwake = false

    /**
     * 使屏幕保持常亮
     * @param activity 需要保持屏幕常亮的Activity
     * @return 是否设置成功
     */
    fun keepScreenAwake(activity: Activity): Boolean {
        if (isKeepingAwake) {
            AppLogger.getInstance().d("$TAG 屏幕已处于常亮状态，无需重复设置")
            return true
        }

        try {
            // 保存当前系统的屏幕超时设置
            originalTimeout = getScreenOffTimeout(activity)
            AppLogger.getInstance().d("$TAG 保存原始超时设置: $originalTimeout ms")
            // 方法1: 设置窗口FLAG (推荐)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // 记录状态变更
            isKeepingAwake = true
            AppLogger.getInstance().d("$TAG 屏幕常亮模式已启用")
            return true
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG 设置屏幕常亮失败 , ${e.message}")
            return false
        }
    }

    /**
     * 恢复屏幕正常超时设置
     * @param activity 需要恢复的Activity
     * @return 是否恢复成功
     */
    fun restoreScreenTimeout(activity: Activity): Boolean {
        if (!isKeepingAwake) {
            AppLogger.getInstance().d("$TAG 屏幕未处于常亮状态，无需恢复")
            return true
        }

        try {
            // 清除窗口FLAG
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // 恢复状态
            isKeepingAwake = false
            AppLogger.getInstance().d("$TAG 屏幕常亮模式已关闭")
            return true
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG 恢复屏幕超时设置失败 , ${e.message}")
            return false
        }
    }

    /**
     * 获取当前系统屏幕超时设置
     */
    private fun getScreenOffTimeout(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT
            )
        } catch (e: Exception) {
            AppLogger.getInstance().e("$TAG 获取屏幕超时设置失败 , ${e.message}")
            -1
        }
    }

    /**
     * 检查当前是否处于常亮状态
     */
    fun isKeepingAwake(): Boolean {
        return isKeepingAwake
    }
}