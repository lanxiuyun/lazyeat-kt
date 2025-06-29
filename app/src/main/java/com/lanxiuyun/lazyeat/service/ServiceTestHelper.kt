package com.lanxiuyun.lazyeat.service

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * 服务测试辅助类
 * 用于调试和测试前台服务功能
 */
object ServiceTestHelper {
    
    private const val TAG = "ServiceTestHelper"
    
    /**
     * 测试服务启动
     * @param context 上下文
     */
    fun testServiceStart(context: Context) {
        try {
            Log.d(TAG, "开始测试服务启动")
            
            // 检查权限
            if (!hasRequiredPermissions(context)) {
                Log.e(TAG, "缺少必要权限")
                Toast.makeText(context, "缺少必要权限，请先授予权限", Toast.LENGTH_LONG).show()
                return
            }
            
            // 启动服务
            ServiceManager.startGestureRecognitionService(context)
            
            // 等待一段时间后检查服务状态
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val isRunning = ServiceManager.isGestureRecognitionServiceRunning(context)
                Log.d(TAG, "服务运行状态: $isRunning")
                
                if (isRunning) {
                    Toast.makeText(context, "服务启动成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "服务启动失败", Toast.LENGTH_SHORT).show()
                }
            }, 2000)
            
        } catch (e: Exception) {
            Log.e(TAG, "测试服务启动失败: ${e.message}")
            Toast.makeText(context, "测试失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 测试服务停止
     * @param context 上下文
     */
    fun testServiceStop(context: Context) {
        try {
            Log.d(TAG, "开始测试服务停止")
            
            // 停止服务
            ServiceManager.stopGestureRecognitionService(context)
            
            // 等待一段时间后检查服务状态
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val isRunning = ServiceManager.isGestureRecognitionServiceRunning(context)
                Log.d(TAG, "服务运行状态: $isRunning")
                
                if (!isRunning) {
                    Toast.makeText(context, "服务停止成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "服务停止失败", Toast.LENGTH_SHORT).show()
                }
            }, 1000)
            
        } catch (e: Exception) {
            Log.e(TAG, "测试服务停止失败: ${e.message}")
            Toast.makeText(context, "测试失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 检查服务状态
     * @param context 上下文
     */
    fun checkServiceStatus(context: Context) {
        val isRunning = ServiceManager.isGestureRecognitionServiceRunning(context)
        val status = if (isRunning) "运行中" else "已停止"
        
        Log.d(TAG, "服务状态: $status")
        Toast.makeText(context, "服务状态: $status", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 检查必要权限
     * @param context 上下文
     * @return 是否有必要权限
     */
    private fun hasRequiredPermissions(context: Context): Boolean {
        val permissions = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.FOREGROUND_SERVICE
        )
        
        return permissions.all { permission ->
            context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 打印调试信息
     * @param context 上下文
     */
    fun printDebugInfo(context: Context) {
        Log.d(TAG, "=== 调试信息 ===")
        Log.d(TAG, "应用包名: ${context.packageName}")
        Log.d(TAG, "Android版本: ${android.os.Build.VERSION.SDK_INT}")
        Log.d(TAG, "设备型号: ${android.os.Build.MODEL}")
        Log.d(TAG, "服务运行状态: ${ServiceManager.isGestureRecognitionServiceRunning(context)}")
        Log.d(TAG, "当前手势结果: ${GestureRecognitionService.currentGestureResult}")
        Log.d(TAG, "手部识别结果: ${GestureRecognitionService.lastHandLandmarkerResult != null}")
        Log.d(TAG, "=================")
    }
} 