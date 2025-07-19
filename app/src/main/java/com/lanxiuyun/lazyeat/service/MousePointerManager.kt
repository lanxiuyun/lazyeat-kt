package com.lanxiuyun.lazyeat.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.lanxiuyun.lazyeat.utils.LogUtils

object MousePointerManager {
    private const val TAG = "MousePointerManager"

    /**
     * 检查是否有悬浮窗权限
     */
    fun checkOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * 启动鼠标指针服务
     */
    fun startMousePointerService(context: Context) {
        try {
            if (!checkOverlayPermission(context)) {
                LogUtils.e(TAG, "没有悬浮窗权限")
                return
            }

            LogUtils.i(TAG, "启动鼠标指针服务")
            val serviceIntent = Intent(context, MousePointerService::class.java)
            context.startService(serviceIntent)
        } catch (e: Exception) {
            LogUtils.e(TAG, "启动鼠标指针服务失败: ${e.message}")
            throw e
        }
    }

    /**
     * 停止鼠标指针服务
     */
    fun stopMousePointerService(context: Context) {
        try {
            LogUtils.i(TAG, "停止鼠标指针服务")
            val serviceIntent = Intent(context, MousePointerService::class.java)
            context.stopService(serviceIntent)
        } catch (e: Exception) {
            LogUtils.e(TAG, "停止鼠标指针服务失败: ${e.message}")
            throw e
        }
    }
} 