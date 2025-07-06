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
    }

    /**
     * 初始化 HandLandmarker
     * 必须先把 hand_landmarker.task 放到 assets 目录下
     */
    fun initialize() {
        try {
            LogUtils.i(TAG, "开始初始化 HandLandmarker")
            
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
            
            // 构建 BaseOptions（官方推荐方式）
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelFile.absolutePath)
                .build()
            LogUtils.d(TAG, "BaseOptions 构建完成")
            
            // 构建 HandLandmarkerOptions（官方推荐方式）
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result: HandLandmarkerResult?, image: MPImage ->
                    // 回调在子线程，切回主线程
                    LogUtils.d(TAG, "收到手势识别结果回调")
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
            LogUtils.d(TAG, "HandLandmarkerOptions 构建完成")
            
            handLandmarker = HandLandmarker.createFromOptions(context, options)
            isInitialized = true
            LogUtils.i(TAG, "HandLandmarker 初始化成功")
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