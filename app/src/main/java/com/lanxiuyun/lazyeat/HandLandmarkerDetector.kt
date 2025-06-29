package com.lanxiuyun.lazyeat

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
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

    /**
     * 初始化 HandLandmarker
     * 必须先把 hand_landmarker.task 放到 assets 目录下
     */
    fun initialize() {
        try {
            // 复制模型到 cache 目录（MediaPipe 只能用文件路径）
            val modelAssetName = "hand_landmarker.task"
            val modelFile = File(context.cacheDir, modelAssetName)
            if (!modelFile.exists()) {
                context.assets.open(modelAssetName).use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            // 构建 BaseOptions（官方推荐方式）
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelFile.absolutePath)
                .build()
            // 构建 HandLandmarkerOptions（官方推荐方式）
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ ->
                    // 回调在子线程，切回主线程
                    mainHandler.post {
                        onResult(result)
                    }
                }
                .setErrorListener { error ->
                    Log.e("HandLandmarkerDetector", "手部关键点检测错误: ${error.message}")
                }
                .build()
            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.d("HandLandmarkerDetector", "HandLandmarker 初始化成功")
        } catch (e: Exception) {
            Log.e("HandLandmarkerDetector", "HandLandmarker 初始化失败: ${e.message}")
        }
    }

    /**
     * 检测手部关键点
     * @param bitmap CameraX 转换得到的 Bitmap
     * @param timestamp 时间戳
     */
    fun detect(bitmap: Bitmap, timestamp: Long) {
        try {
            // 官方推荐的 MPImage 创建方式
            val mpImage = BitmapImageBuilder(bitmap).build()
            handLandmarker?.detectAsync(mpImage, timestamp)
        } catch (e: Exception) {
            Log.e("HandLandmarkerDetector", "检测失败: ${e.message}")
        }
    }

    fun release() {
        try {
            handLandmarker?.close()
            Log.d("HandLandmarkerDetector", "HandLandmarker 资源已释放")
        } catch (e: Exception) {
            Log.e("HandLandmarkerDetector", "释放资源失败: ${e.message}")
        }
    }
} 