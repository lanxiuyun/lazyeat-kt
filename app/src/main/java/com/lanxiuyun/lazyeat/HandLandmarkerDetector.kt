package com.lanxiuyun.lazyeat

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.lanxiuyun.lazyeat.utils.LogUtils
import java.io.File

/**
 * 手部关键点检测器，基于 MediaPipe HandLandmarker
 * 
 * 优化配置：
 * - 设置更高的检测置信度阈值
 * - 限制只检测单手（提高稳定性）
 * - 启用跟踪模式减少抖动
 * 
 * @param context 上下文
 * @param onResult 检测到手部关键点时的回调
 */
class HandLandmarkerDetector(
    private val context: Context,
    private val onResult: (HandLandmarkerResult?) -> Unit
) {
    private var handLandmarker: HandLandmarker? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isInitialized = false

    companion object {
        private const val TAG = "HandLandmarkerDetector"
        private const val MODEL_NAME = "hand_landmarker.task"

        // ========== 检测质量阈值配置 ==========
        const val MIN_DETECTION_CONFIDENCE = 0.5f      // 最小检测置信度（提高以过滤弱检测）
        const val MIN_TRACKING_CONFIDENCE = 0.5f       // 最小跟踪置信度（提高以保持稳定）
        const val MIN_PRESENCE_CONFIDENCE = 0.5f       // 最小存在置信度（提高以减少误检）
        const val MAX_NUM_HANDS = 1                      // 只检测一只手（减少干扰，提高稳定性）
        const val RUNNING_MODE = "LIVE_STREAM"          // 实时流模式
    }

    /**
     * 初始化 HandLandmarker
     * 必须先把 hand_landmarker.task 放到 assets 目录下
     */
    fun initialize() {
        try {
            LogUtils.i(TAG, "开始初始化 HandLandmarker (优化配置)")
            
            // 复制模型到 cache 目录（MediaPipe 只能用文件路径）
            val modelFile = File(context.cacheDir, MODEL_NAME)
            
            if (!modelFile.exists()) {
                LogUtils.i(TAG, "模型文件不存在，从 assets 复制到 cache 目录")
                context.assets.open(MODEL_NAME).use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                LogUtils.i(TAG, "模型文件复制完成: ${modelFile.absolutePath}")
            } else {
                LogUtils.d(TAG, "模型文件已存在: ${modelFile.absolutePath}")
            }
            
            // 构建 BaseOptions
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelFile.absolutePath)
                .build()
            LogUtils.d(TAG, "BaseOptions 构建完成")
            
            // 构建 HandLandmarkerOptions（使用更严格的配置）
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                // 严格的质量阈值配置
                .setMinHandDetectionConfidence(MIN_DETECTION_CONFIDENCE)
                .setMinHandPresenceConfidence(MIN_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                // 只检测一只手，减少干扰
                .setNumHands(MAX_NUM_HANDS)
                .setResultListener { result: HandLandmarkerResult?, image: MPImage ->
                    // 回调在子线程，切回主线程
                    LogUtils.d(TAG, "收到手势识别结果回调 - hands: ${result?.landmarks()?.size ?: 0}")
                    mainHandler.post {
                        onResult(result)
                    }
                }
                .setErrorListener { error ->
                    LogUtils.e(TAG, "手部关键点检测错误: ${error.message}")
                    mainHandler.post {
                        onResult(null)
                    }
                }
                .build()
            LogUtils.d(TAG, "HandLandmarkerOptions 构建完成 (置信度阈值: ${MIN_DETECTION_CONFIDENCE})")
            
            handLandmarker = HandLandmarker.createFromOptions(context, options)
            isInitialized = true
            LogUtils.i(TAG, "HandLandmarker 初始化成功 (maxHands=${MAX_NUM_HANDS}, " +
                    "minDetection=${MIN_DETECTION_CONFIDENCE}, " +
                    "minTracking=${MIN_TRACKING_CONFIDENCE})")
        } catch (e: Exception) {
            LogUtils.e(TAG, "HandLandmarker 初始化失败: ${e.message}")
            e.printStackTrace()
            isInitialized = false
        }
    }

    /**
     * 检测手部关键点
     * @param bitmap CameraX 转换得到的 Bitmap
     * @param timestamp 时间戳
     */
    fun detect(bitmap: Bitmap, timestamp: Long) {
        if (!isInitialized) {
            LogUtils.w(TAG, "HandLandmarker 未初始化，跳过检测")
            return
        }
        
        try {
            LogUtils.d(TAG, "开始检测手部关键点，图像尺寸: ${bitmap.width}x${bitmap.height}")
            
            // 官方推荐的 MPImage 创建方式
            val mpImage = BitmapImageBuilder(bitmap).build()
            handLandmarker?.detectAsync(mpImage, timestamp)
            
            LogUtils.d(TAG, "异步检测请求已发送")
        } catch (e: Exception) {
            LogUtils.e(TAG, "检测失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 重新初始化检测器（用于动态调整参数）
     */
    fun reinitialize() {
        LogUtils.i(TAG, "重新初始化 HandLandmarker")
        release()
        initialize()
    }

    /**
     * 获取当前配置信息
     */
    fun getConfigInfo(): String {
        return "maxHands=$MAX_NUM_HANDS, minDetection=$MIN_DETECTION_CONFIDENCE, " +
               "minTracking=$MIN_TRACKING_CONFIDENCE, minPresence=$MIN_PRESENCE_CONFIDENCE"
    }

    fun release() {
        try {
            handLandmarker?.close()
            isInitialized = false
            LogUtils.i(TAG, "HandLandmarker 资源已释放")
        } catch (e: Exception) {
            LogUtils.e(TAG, "释放资源失败: ${e.message}")
            e.printStackTrace()
        }
    }
}