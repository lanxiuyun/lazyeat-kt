package com.lanxiuyun.lazyeat.service

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lanxiuyun.lazyeat.utils.LogUtils

/**
 * 服务测试助手
 * 用于测试和调试手势识别服务
 */
class ServiceTestHelper(private val context: Context) {
    companion object {
        private const val TAG = "ServiceTestHelper"
    }
    
    /**
     * 测试服务启动
     */
    fun testStartService() {
        LogUtils.i(TAG, "开始测试服务启动")
        
        // 检查权限
        if (!checkPermissions()) {
            LogUtils.e(TAG, "缺少必要权限")
            return
        }
        
        try {
            // 启动服务
            ServiceManager.startGestureRecognitionService(context)
            
            // 等待服务启动（最多等待5秒）
            var isRunning = false
            for (i in 1..10) {
                Thread.sleep(500)
                isRunning = ServiceManager.isGestureRecognitionServiceRunning(context)
                LogUtils.i(TAG, "服务运行状态: $isRunning")
                if (isRunning) break
            }
            
            // 检查服务状态
            if (!isRunning) {
                throw Exception("服务启动超时")
            }
            
        } catch (e: Exception) {
            LogUtils.e(TAG, "测试服务启动失败: ${e.message}")
            throw e
        }
    }
    
    /**
     * 测试服务停止
     */
    fun testStopService() {
        LogUtils.i(TAG, "开始测试服务停止")
        
        try {
            // 停止服务
            ServiceManager.stopGestureRecognitionService(context)
            
            // 等待服务停止（最多等待5秒）
            var isRunning = true
            for (i in 1..10) {
                Thread.sleep(500)
                isRunning = ServiceManager.isGestureRecognitionServiceRunning(context)
                LogUtils.i(TAG, "服务运行状态: $isRunning")
                if (!isRunning) break
            }
            
            // 检查服务状态
            if (isRunning) {
                throw Exception("服务停止超时")
            }
            
        } catch (e: Exception) {
            LogUtils.e(TAG, "测试服务停止失败: ${e.message}")
            throw e
        }
    }
    
    /**
     * 检查服务状态
     */
    fun checkServiceStatus(): String {
        val status = StringBuilder()
        
        // 检查服务运行状态
        val isRunning = ServiceManager.isGestureRecognitionServiceRunning(context)
        status.append("服务运行中: $isRunning\n")
        
        LogUtils.i(TAG, "服务状态: $status")
        return status.toString()
    }
    
    /**
     * 检查必要权限
     */
    private fun checkPermissions(): Boolean {
        val permissions = arrayOf(
            android.Manifest.permission.CAMERA
        )
        
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 打印调试信息
     */
    fun printDebugInfo() {
        LogUtils.i(TAG, "=== 调试信息 ===")
        LogUtils.i(TAG, "服务运行状态: ${ServiceManager.isGestureRecognitionServiceRunning(context)}")
        LogUtils.i(TAG, "当前手势结果: ${GestureRecognitionService.currentGestureResult}")
        LogUtils.i(TAG, "手部识别结果: ${GestureRecognitionService.lastHandLandmarkerResult != null}")
        LogUtils.i(TAG, "=================")
    }
} 