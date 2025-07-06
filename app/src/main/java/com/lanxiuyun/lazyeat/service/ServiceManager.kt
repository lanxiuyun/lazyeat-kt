package com.lanxiuyun.lazyeat.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.lanxiuyun.lazyeat.utils.LogUtils

/**
 * 服务管理器
 * 负责手势识别服务的启动、停止和状态查询
 */
object ServiceManager {
    private const val TAG = "ServiceManager"
    
    /**
     * 启动手势识别服务
     */
    fun startGestureRecognitionService(context: Context) {
        try {
            LogUtils.i(TAG, "启动手势识别服务")
            
            // 如果服务已在运行，则不重复启动
            if (isGestureRecognitionServiceRunning(context)) {
                return
            }
            
            // 创建启动服务的Intent
            val serviceIntent = Intent(context, GestureRecognitionService::class.java)
            
            // 启动前台服务
            context.startForegroundService(serviceIntent)
            
            LogUtils.i(TAG, "手势识别服务启动成功")
        } catch (e: Exception) {
            LogUtils.e(TAG, "启动手势识别服务失败: ${e.message}")
            throw e
        }
    }
    
    /**
     * 停止手势识别服务
     */
    fun stopGestureRecognitionService(context: Context) {
        try {
            LogUtils.i(TAG, "停止手势识别服务")
            
            val serviceIntent = Intent(context, GestureRecognitionService::class.java)
            context.stopService(serviceIntent)
            
            LogUtils.i(TAG, "手势识别服务停止成功")
        } catch (e: Exception) {
            LogUtils.e(TAG, "停止手势识别服务失败: ${e.message}")
            throw e
        }
    }
    
    /**
     * 检查手势识别服务是否正在运行
     */
    fun isGestureRecognitionServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == GestureRecognitionService::class.java.name }
    }
} 