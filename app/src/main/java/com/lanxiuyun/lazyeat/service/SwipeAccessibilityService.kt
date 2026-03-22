package com.lanxiuyun.lazyeat.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.lanxiuyun.lazyeat.utils.LogUtils

/**
 * 滑动辅助功能服务 - 吃饭刷抖音助手
 *
 * 此服务作为 AccessibilityService，用于接收手势识别服务的广播并执行系统级滑动操作。
 * 用户需要在系统设置中手动开启此辅助功能。
 *
 * 功能：
 * - 接收来自 GestureRecognitionService 的滑动广播
 * - 在屏幕中央执行向上/向下滑动手势
 * - 支持抖音等短视频应用的切换控制
 */
class SwipeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SwipeAccessibilityService"
        const val ACTION_SWIPE_GESTURE = "com.lanxiuyun.lazyeat.SWIPE_GESTURE"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_DURATION = "duration"
    }

    // 广播接收器，接收来自 GestureRecognitionService 的滑动指令
    private val swipeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SWIPE_GESTURE) {
                val direction = intent.getStringExtra(EXTRA_DIRECTION) ?: return
                val distance = intent.getIntExtra(EXTRA_DISTANCE, 800)
                val duration = intent.getLongExtra(EXTRA_DURATION, 300L)

                LogUtils.i(TAG, "接收到滑动广播: direction=$direction, distance=$distance, duration=$duration")

                when (direction) {
                    "up" -> performSwipeUp(distance, duration)
                    "down" -> performSwipeDown(distance, duration)
                    else -> LogUtils.w(TAG, "未知的滑动方向: $direction")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogUtils.i(TAG, "滑动辅助功能服务已连接")
        LogUtils.i(TAG, "辅助功能服务信息: ${serviceInfo}")

        // 注册广播接收器（应用内部使用，不导出）
        try {
            val filter = IntentFilter(ACTION_SWIPE_GESTURE)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(swipeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(swipeReceiver, filter)
            }
            LogUtils.i(TAG, "滑动广播接收器已注册")
        } catch (e: Exception) {
            LogUtils.e(TAG, "注册广播接收器失败: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理具体的事件，只用于执行手势
        // 但保留此方法以维持服务的活动状态
    }

    override fun onInterrupt() {
        LogUtils.w(TAG, "辅助功能服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LogUtils.w(TAG, "辅助功能服务被解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtils.i(TAG, "滑动辅助功能服务已销毁")

        // 注销广播接收器
        try {
            unregisterReceiver(swipeReceiver)
        } catch (e: Exception) {
            LogUtils.e(TAG, "注销广播接收器失败: ${e.message}")
        }
    }

    /**
     * 执行向上滑动
     * 用于切换到上一个视频（抖音中向上滑动是下一个视频，这里根据实际需求调整）
     */
    private fun performSwipeUp(distance: Int, duration: Long) {
        LogUtils.i(TAG, "执行向上滑动: distance=$distance, duration=$duration")

        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f

        // 创建从下到上的滑动路径
        val path = Path().apply {
            moveTo(centerX, centerY + distance / 2f)
            lineTo(centerX, centerY - distance / 2f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                LogUtils.i(TAG, "向上滑动完成")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                LogUtils.w(TAG, "向上滑动被取消")
            }
        }, null)

        if (!result) {
            LogUtils.e(TAG, "向上滑动请求失败")
        }
    }

    /**
     * 执行向下滑动
     * 用于切换到下一个视频
     */
    private fun performSwipeDown(distance: Int, duration: Long) {
        LogUtils.i(TAG, "执行向下滑动: distance=$distance, duration=$duration")

        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f

        // 创建从上到下的滑动路径
        val path = Path().apply {
            moveTo(centerX, centerY - distance / 2f)
            lineTo(centerX, centerY + distance / 2f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                LogUtils.i(TAG, "向下滑动完成")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                LogUtils.w(TAG, "向下滑动被取消")
            }
        }, null)

        if (!result) {
            LogUtils.e(TAG, "向下滑动请求失败")
        }
    }
}
