package com.lanxiuyun.lazyeat.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import com.lanxiuyun.lazyeat.R
import com.lanxiuyun.lazyeat.utils.LogUtils
import kotlin.math.abs
import android.os.Handler
import android.os.Looper

class MousePointerService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var mousePointerView: ImageView
    private var isPointerShowing = false
    
    // 添加平滑移动相关变量
    private var currentX = 0
    private var currentY = 0
    private var targetX = 0
    private var targetY = 0
    private val smoothFactor = 0.3f  // 平滑系数，值越小移动越平滑
    private val movementThreshold = 3 // 移动阈值，小于此值的移动将被忽略
    
    // 添加移动平均值计算相关变量
    private val positionHistorySize = 5  // 历史位置记录数量
    private val xHistory = ArrayDeque<Int>(positionHistorySize)
    private val yHistory = ArrayDeque<Int>(positionHistorySize)

    // 添加主线程Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    companion object {
        private const val TAG = "MousePointerService"
        private const val UPDATE_INTERVAL = 16L // 约60fps的更新频率
    }

    override fun onCreate() {
        super.onCreate()
        LogUtils.i(TAG, "鼠标指针服务创建")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createMousePointerView()
        startSmoothUpdateLoop()
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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,  // 添加这个标志允许超出屏幕边界
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

    private fun startSmoothUpdateLoop() {
        updateRunnable = object : Runnable {
            override fun run() {
                updateSmoothPosition()
                // 如果服务还在运行，继续下一次更新
                if (isPointerShowing) {
                    mainHandler.postDelayed(this, UPDATE_INTERVAL)
                }
            }
        }
        
        // 开始更新循环
        updateRunnable?.let { mainHandler.post(it) }
    }

    private fun updateSmoothPosition() {
        if (!isPointerShowing) return

        try {
            // 计算平滑移动的新位置
            val dx = (targetX - currentX) * smoothFactor
            val dy = (targetY - currentY) * smoothFactor

            // 只有当移动距离超过阈值时才更新位置
            if (abs(dx) > movementThreshold || abs(dy) > movementThreshold) {
                currentX += dx.toInt()
                currentY += dy.toInt()

                val params = mousePointerView.layoutParams as WindowManager.LayoutParams
                
                // 获取屏幕尺寸
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                
                // 限制坐标范围
                params.x = currentX.coerceIn(0, screenWidth - 10)
                params.y = currentY.coerceIn(0, screenHeight - 200)
                
                windowManager.updateViewLayout(mousePointerView, params)
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "更新鼠标位置失败: ${e.message}")
        }
    }

    private fun calculateMovingAverage(value: Int, history: ArrayDeque<Int>): Int {
        // 添加新值到历史记录
        if (history.size >= positionHistorySize) {
            history.removeFirst()
        }
        history.addLast(value)
        
        // 计算移动平均值
        return history.average().toInt()
    }

    fun updatePointerPosition(x: Int, y: Int) {
        if (!isPointerShowing) return

        // 使用移动平均值来平滑坐标
        targetX = calculateMovingAverage(x, xHistory)
        targetY = calculateMovingAverage(y, yHistory)
        
        LogUtils.d(TAG, "更新目标位置: x=$targetX, y=$targetY")
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
        // 移除所有待处理的更新
        updateRunnable?.let { mainHandler.removeCallbacks(it) }
        
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