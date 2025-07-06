package com.lanxiuyun.lazyeat.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.lanxiuyun.lazyeat.HandLandmarkerDetector
import com.lanxiuyun.lazyeat.MainActivity
import com.lanxiuyun.lazyeat.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import android.graphics.BitmapFactory

/**
 * 手势识别前台服务
 * 
 * 这是一个持续运行的前台服务，用于实时手势识别。主要功能包括：
 * 1. 摄像头管理：初始化和控制前置摄像头
 * 2. 图像处理：定期捕获和处理摄像头图像
 * 3. 手势识别：使用 MediaPipe 进行实时手势检测
 * 4. 状态通知：在通知栏显示实时识别结果
 * 5. 数据共享：与主界面共享识别结果
 *
 * 技术特点：
 * - 使用 CameraX API 进行相机操作
 * - 采用 LifecycleService 确保正确的生命周期管理
 * - 实现前台服务确保持续运行
 */
class GestureRecognitionService : LifecycleService() {
    
    // 相机相关组件
    private var cameraProvider: ProcessCameraProvider? = null  // 相机提供者，用于管理相机生命周期
    private var camera: Camera? = null                        // 相机实例
    private var imageCapture: ImageCapture? = null           // 图像捕获用例
    private var imageAnalysis: ImageAnalysis? = null         // 新增：图像分析用例（连续帧）
    private lateinit var cameraExecutor: ExecutorService     // 相机操作的执行器
    private lateinit var handLandmarkerDetector: HandLandmarkerDetector  // 手势识别器
    
    // 通知管理器，用于处理前台服务通知
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }
    
    companion object {
        private const val TAG = "GestureRecognitionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "gesture_recognition_channel"
        
        // 与主界面共享的状态数据
        @Volatile
        var currentGestureResult: String = "等待手势识别..."  // 当前识别结果
        @Volatile
        var lastHandLandmarkerResult: HandLandmarkerResult? = null  // 最近一次的详细识别数据
        @Volatile
        var lastPreviewImage: Bitmap? = null  // 最近一次的预览图像
    }
    
    /**
     * 服务创建时的初始化
     * 完成相机执行器、手势识别器和通知通道的初始化
     */
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "手势识别服务创建")
        
        // 创建单线程执行器用于相机操作
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // 初始化手势识别器并设置结果回调
        handLandmarkerDetector = HandLandmarkerDetector(this) { result ->
            handleGestureResult(result)
        }
        handLandmarkerDetector.initialize()
        
        // 创建通知通道（Android 8.0及以上必需）
        createNotificationChannel()
    }
    
    /**
     * 服务启动时的处理
     * 启动前台服务并开始相机捕获
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "手势识别服务启动")
        
        // 将服务升级为前台服务，避免被系统回收
        startForeground(NOTIFICATION_ID, createNotification("手势识别服务运行中"))
        
        // 启动相机并开始识别
        startCamera()
        
        return START_STICKY  // 服务被系统终止后会尝试重新创建
    }
    
    /**
     * 服务销毁时的清理工作
     * 释放相机和识别器资源
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "手势识别服务销毁")
        
        cameraExecutor.shutdown()  // 关闭相机执行器
        handLandmarkerDetector.release()  // 释放识别器资源
    }
    
    /**
     * 创建通知渠道
     * Android 8.0（API 26）及以上版本必需
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "手势识别服务",
                NotificationManager.IMPORTANCE_LOW  // 使用低重要度避免打扰用户
            ).apply {
                description = "显示手势识别结果"
                setShowBadge(false)  // 不显示角标
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建通知
     * @param content 通知内容文本
     * @return 配置好的通知对象
     */
    private fun createNotification(content: String): Notification {
        // 创建打开主界面的意图
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        // 创建PendingIntent，点击通知时使用
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 构建并返回通知
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手势识别")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // 设置为持续通知
            .build()
    }
    
    /**
     * 初始化并启动相机
     * 配置相机参数并开始图像捕获
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // 创建连续图像流 (ImageAnalysis) 用例，替代 ImageCapture
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetRotation(getDisplayRotation())  // 设置正确方向，防止图像旋转错误
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)  // 4:3 比例，与模型训练一致
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)  // 只处理最新一帧，降低延迟
                    // 默认输出 YUV_420_888（不指定格式），便于统一处理
                    .build().apply {
                        // 每当摄像头产生新帧时都会调用此分析器
                        setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(imageProxy)  // 处理并执行手势识别
                        }
                    }
                
                // 配置前置相机
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)  // 前置摄像头
                    .build()
                
                // 将相机用例绑定到生命周期（只绑定 preview & analysis 即可）
                camera = cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
                )
                
                this.imageAnalysis = imageAnalysis
                
                Log.i(TAG, "摄像头启动成功")
                
            } catch (e: Exception) {
                Log.e(TAG, "摄像头启动失败: ${e.message}")
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    /**
     * 获取屏幕旋转角度
     * @return Surface.ROTATION_* 常量值
     */
    private fun getDisplayRotation(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11及以上使用新API
                this.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取屏幕旋转失败，使用默认值: ${e.message}")
            Surface.ROTATION_0  // 获取失败时使用默认值
        }
    }
    
    /**
     * 将ImageProxy转换为Bitmap
     * @param imageProxy 相机捕获的图像代理对象
     * @return 转换后的Bitmap，失败返回null
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            if (imageProxy.format == ImageFormat.YUV_420_888 && imageProxy.planes.size == 3) {
                // ---- YUV_420_888 → NV21 → Bitmap ----
                val nv21 = yuv420ThreePlanesToNV21(
                    imageProxy.planes,
                    imageProxy.width,
                    imageProxy.height
                )
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
                val imageBytes = out.toByteArray()
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            } else {
                // ---- 其他格式（如 RGBA_8888）→ 直接拷贝 ----
                val plane = imageProxy.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * imageProxy.width
                val bitmap = Bitmap.createBitmap(
                    imageProxy.width + rowPadding / pixelStride,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ImageProxy 转 Bitmap 失败: ${e.message}")
            null
        }
    }
    
    /**
     * 将 CameraX 提供的三平面 YUV 数据转成 NV21 格式
     * 说明：NV21 是 Android 里使用最广泛的 YUV 格式，便于后续使用 YuvImage 进行压缩
     */
    private fun yuv420ThreePlanesToNV21(
        planes: Array<ImageProxy.PlaneProxy>,
        width: Int,
        height: Int
    ): ByteArray {
        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)  // NV21 = Y + VU

        // ----- 1. Y 平面 -----
        val yBuffer = planes[0].buffer
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride  // 对于 Y 通道通常为1
        var pos = 0
        for (row in 0 until height) {
            if (yPixelStride == 1 && yRowStride == width) {
                // 连续内存，直接拷贝整行
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            } else {
                // 逐像素读取
                for (col in 0 until width) {
                    nv21[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                }
            }
        }

        // ----- 2. UV 平面（VU 排列）-----
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vuPos = row * uvRowStride + col * uvPixelStride
                nv21[pos++] = vBuffer.get(vuPos)  // V 分量
                nv21[pos++] = uBuffer.get(vuPos)  // U 分量
            }
        }

        return nv21
    }
    
    /**
     * 处理 ImageProxy（连续帧）并执行手势识别
     * 注意：必须在最后调用 image.close() 否则下一帧无法到达
     */
    private fun processImageProxy(image: ImageProxy) {
        try {
            // 1. ImageProxy -> Bitmap
            val bitmap = imageProxyToBitmap(image)?.let { original ->
                // 读取当帧需要顺时针旋转的角度
                val rotationDegrees = image.imageInfo.rotationDegrees
                // 1) 先旋转至正确方向，2) 再做前置镜像
                val matrix = Matrix().apply {
                    // 将图像旋转到自然朝向
                    postRotate(rotationDegrees.toFloat())
                    // 前置摄像头保持与真人一致的左右方向
                    postScale(-1f, 1f, original.width / 2f, original.height / 2f)
                }
                Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            }

            if (bitmap != null) {
                // 更新最近预览
                lastPreviewImage = bitmap
                // 执行手势识别（异步）
                handLandmarkerDetector.detect(bitmap, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "图像处理失败: ${e.message}")
        } finally {
            // VERY VERY IMPORTANT：释放当前帧资源
            image.close()
        }
    }
    
    /**
     * 处理手势识别结果
     * @param result MediaPipe手势识别结果
     */
    private fun handleGestureResult(result: HandLandmarkerResult?) {
        lastHandLandmarkerResult = result
        
        // 生成识别结果文本
        val gestureText = if (result != null && result.landmarks().isNotEmpty()) {
            val handCount = result.landmarks().size
            "检测到 $handCount 只手"
        } else {
            "未检测到手部"
        }
        
        // 更新状态和通知
        currentGestureResult = gestureText
        updateNotification(gestureText)
        
        Log.d(TAG, "手势识别结果: $gestureText")
    }
    
    /**
     * 更新通知内容
     * @param content 新的通知内容
     */
    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
} 