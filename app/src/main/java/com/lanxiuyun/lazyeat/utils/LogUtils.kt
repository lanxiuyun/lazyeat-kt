package com.lanxiuyun.lazyeat.utils

import android.util.Log

/**
 * 日志工具类
 * 统一管理应用中的日志输出
 */
object LogUtils {
    // 日志等级
    private var logLevel: Int = Log.WARN

    /**
     * 设置日志等级
     */
    fun setLogLevel(level: Int) {
        logLevel = level
    }

    /**
     * 记录日志
     */
    private fun log(tag: String, priority: Int, message: String) {
        if (priority >= logLevel) {
            Log.println(priority, tag, message)
        }
    }

    // 便捷方法
    fun v(tag: String, message: String) = log(tag, Log.VERBOSE, message)
    fun d(tag: String, message: String) = log(tag, Log.DEBUG, message)
    fun i(tag: String, message: String) = log(tag, Log.INFO, message)
    fun w(tag: String, message: String) = log(tag, Log.WARN, message)
    fun e(tag: String, message: String) = log(tag, Log.ERROR, message)
} 