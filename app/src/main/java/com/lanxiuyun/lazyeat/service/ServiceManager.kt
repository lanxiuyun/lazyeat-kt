package com.lanxiuyun.lazyeat.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 服务管理器
 * 负责控制手势识别服务的启动和停止
 */
object ServiceManager {
    
    private const val TAG = "ServiceManager"
    
    /**
     * 启动手势识别服务
     * @param context 上下文
     */
    fun startGestureRecognitionService(context: Context) {
        try {
            Log.d(TAG, "启动手势识别服务")
            
            val serviceIntent = Intent(context, GestureRecognitionService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+ 使用 startForegroundService
                context.startForegroundService(serviceIntent)
            } else {
                // Android 8.0 以下使用 startService
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "手势识别服务启动成功")
        } catch (e: Exception) {
            Log.e(TAG, "启动手势识别服务失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 停止手势识别服务
     * @param context 上下文
     */
    fun stopGestureRecognitionService(context: Context) {
        try {
            Log.d(TAG, "停止手势识别服务")
            
            val serviceIntent = Intent(context, GestureRecognitionService::class.java)
            context.stopService(serviceIntent)
            
            Log.d(TAG, "手势识别服务停止成功")
        } catch (e: Exception) {
            Log.e(TAG, "停止手势识别服务失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 检查服务是否正在运行
     * @param context 上下文
     * @return 服务是否运行中
     */
    fun isGestureRecognitionServiceRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
        
        return runningServices.any { service ->
            service.service.className == GestureRecognitionService::class.java.name
        }
    }
} 