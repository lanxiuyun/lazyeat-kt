package com.lanxiuyun.lazyeat.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.ImageView
import com.lanxiuyun.lazyeat.R
import com.lanxiuyun.lazyeat.utils.LogUtils

class MousePointerService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var mousePointerView: ImageView
    private var isPointerShowing = false

    companion object {
        private const val TAG = "MousePointerService"
    }

    override fun onCreate() {
        super.onCreate()
        LogUtils.i(TAG, "鼠标指针服务创建")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createMousePointerView()
    }

    private fun createMousePointerView() {
        // 创建鼠标指针视图
        mousePointerView = ImageView(this).apply {
            setImageResource(R.drawable.cursor_48)
        }

        // 设置窗口参数
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 100 // 初始X位置
            y = 100 // 初始Y位置
        }

        try {
            // 添加视图到窗口
            windowManager.addView(mousePointerView, params)
            isPointerShowing = true
            LogUtils.i(TAG, "鼠标指针显示成功")
        } catch (e: Exception) {
            LogUtils.e(TAG, "显示鼠标指针失败: ${e.message}")
        }
    }

    fun updatePointerPosition(x: Int, y: Int) {
        if (!isPointerShowing) return

        try {
            val params = mousePointerView.layoutParams as WindowManager.LayoutParams
            params.x = x
            params.y = y
            windowManager.updateViewLayout(mousePointerView, params)
        } catch (e: Exception) {
            LogUtils.e(TAG, "更新鼠标位置失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "UPDATE_POINTER" -> {
                val x = intent.getIntExtra("x", -1)
                val y = intent.getIntExtra("y", -1)
                if (x != -1 && y != -1) {
                    updatePointerPosition(x, y)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (isPointerShowing) {
            try {
                windowManager.removeView(mousePointerView)
                isPointerShowing = false
                LogUtils.i(TAG, "鼠标指针服务销毁")
            } catch (e: Exception) {
                LogUtils.e(TAG, "移除鼠标指针失败: ${e.message}")
            }
        }
    }
} 