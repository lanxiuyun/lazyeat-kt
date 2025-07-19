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
import com.lanxiuyun.lazyeat.utils.LogUtils
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

    // 添加MousePointerService的Intent
    private val mousePointerIntent by lazy {
        Intent(this, MousePointerService::class.java)
    }
    
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
        
        /**
         * 设置日志等级
         */
        fun setLogLevel(level: Int) {
            LogUtils.setLogLevel(level)
        }
    }
    
    /**
     * 服务创建时的初始化
     * 完成相机执行器、手势识别器和通知通道的初始化
     */
    override fun onCreate() {
        super.onCreate()
        LogUtils.i(TAG, "手势识别服务创建")
        
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
        LogUtils.i(TAG, "手势识别服务启动")
        
        // 启动MousePointerService
        startService(mousePointerIntent)
        
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
        LogUtils.i(TAG, "手势识别服务销毁")
        
        // 停止MousePointerService
        stopService(mousePointerIntent)
        
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
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)  // 4:3 比例，与模型训练一致
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)  // 只处理最新一帧，降低延迟
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)  // 直接输出RGBA格式
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
                
                LogUtils.i(TAG, "摄像头启动成功")
                
            } catch (e: Exception) {
                LogUtils.e(TAG, "摄像头启动失败: ${e.message}")
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    /**
     * 处理 ImageProxy（连续帧）并执行手势识别
     * 注意：必须在最后调用 image.close() 否则下一帧无法到达
     */
    private fun processImageProxy(image: ImageProxy) {
        try {
            // 1. 从 RGBA 格式的 ImageProxy 直接获取 Bitmap
            val bitmap = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            ).apply {
                // 直接从 buffer 拷贝像素数据
                copyPixelsFromBuffer(image.planes[0].buffer)
            }

            // 2. 处理图像旋转和镜像
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val rotation = windowManager.defaultDisplay.rotation
            val degrees = when (rotation) {
                Surface.ROTATION_0 -> 90
                Surface.ROTATION_90 -> 0
                Surface.ROTATION_180 -> 270
                Surface.ROTATION_270 -> 180
                else -> 90
            }
            
            val matrix = Matrix().apply {
                // 前置相机需要水平翻转以保持镜像效果
                postScale(-1f, 1f)
                // 根据设备实际方向旋转
                postRotate(degrees.toFloat())
            }
            
            // 创建旋转和镜像后的新图像
            val processedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )

            // 3. 更新预览和执行识别
            updatePreviewImage(processedBitmap)
            handLandmarkerDetector.detect(processedBitmap, System.currentTimeMillis())

            // 4. 释放原始 bitmap
            bitmap.recycle()
            
        } catch (e: Exception) {
            LogUtils.e(TAG, "图像处理失败: ${e.message}")
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
        updateHandLandmarkerResult(result)
        
        // 生成识别结果文本
        val gestureText = if (result != null && result.landmarks().isNotEmpty()) {
            val handCount = result.landmarks().size
            
            // 获取第一只手的拇指坐标（第4个关键点是拇指尖）
            result.landmarks().firstOrNull()?.let { landmarks ->
                val thumb = landmarks[4]
                // 直接使用归一化坐标
                val viewX = thumb.x()
                val viewY = thumb.y()
                
                // 获取HandOverlayView的尺寸
                MainActivity.handOverlayView?.let { overlayView ->
                    val viewWidth = overlayView.measuredWidth.toFloat()
                    val viewHeight = overlayView.measuredHeight.toFloat()
                    
                    // 获取映射坐标（现在总是返回有效值）
                    val (relativeX, relativeY) = overlayView.getRelativePosition(viewX * viewWidth, viewY * viewHeight)
                    
                    // 获取屏幕尺寸
                    val screenWidth = resources.displayMetrics.widthPixels
                    val screenHeight = resources.displayMetrics.heightPixels
                    
                    // 计算最终屏幕坐标
                    val screenX = (relativeX * screenWidth).toInt()
                    val screenY = (relativeY * screenHeight).toInt()
                    
                    // 发送坐标到MousePointerService
                    val updateIntent = Intent(this, MousePointerService::class.java).apply {
                        action = "UPDATE_POINTER"
                        putExtra("x", screenX as Int)
                        putExtra("y", screenY as Int)
                    }
                    startService(updateIntent)
                    
                    LogUtils.d(TAG, "拇指位置映射: x=$screenX, y=$screenY")
                }
            }
            
            "检测到 $handCount 只手"
        } else {
            "未检测到手部"
        }
        
        // 更新状态和通知
        updateGestureResult(gestureText)
        updateNotification(gestureText)
        
        LogUtils.i(TAG, "手势识别结果: $gestureText")
    }
    
    /**
     * 更新通知内容
     * @param content 新的通知内容
     */
    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * 更新手势识别结果
     */
    private fun updateGestureResult(result: String) {
        currentGestureResult = result
        LogUtils.i(TAG, "手势识别结果: $result")
    }
    
    /**
     * 更新手部识别结果
     */
    private fun updateHandLandmarkerResult(result: HandLandmarkerResult?) {
        lastHandLandmarkerResult = result
        if (result != null) {
            LogUtils.d(TAG, "检测到 ${result.handednesses().size} 只手")
        }
    }
    
    /**
     * 更新预览图像
     */
    private fun updatePreviewImage(image: Bitmap?) {
        lastPreviewImage = image
        if (image != null) {
            LogUtils.v(TAG, "更新预览图像: ${image.width}x${image.height}")
        }
    }
} 