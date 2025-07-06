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
                
                // 配置图像捕获参数
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)  // 优化延迟而非质量
                    .setTargetRotation(getDisplayRotation())  // 设置正确的图像方向
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)  // 使用4:3比例，适合手势识别
                    .build()
                
                // 配置前置相机
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)  // 使用前置相机
                    .build()
                
                // 将相机用例绑定到生命周期
                camera = cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageCapture
                )
                
                this.imageCapture = imageCapture
                
                // 开始定期捕获图像
                startPeriodicImageCapture()
                
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
     * 开始定期图像捕获
     * 每500毫秒捕获一次图像进行识别
     */
    private fun startPeriodicImageCapture() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        val captureRunnable = object : Runnable {
            override fun run() {
                captureImageForGestureRecognition()
                handler.postDelayed(this, 500)  // 每500ms执行一次
            }
        }
        
        handler.post(captureRunnable)
    }
    
    /**
     * 捕获图像并进行手势识别
     * 包含图像捕获、转换和识别过程
     */
    private fun captureImageForGestureRecognition() {
        val imageCapture = imageCapture ?: return
        
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        // 将图像转换为Bitmap并进行镜像处理
                        val bitmap = imageProxyToBitmap(image)?.let { originalBitmap ->
                            // 创建镜像变换矩阵
                            val matrix = Matrix().apply {
                                postScale(-1f, 1f, originalBitmap.width / 2f, originalBitmap.height / 2f)
                            }
                            
                            // 应用镜像变换
                            Bitmap.createBitmap(
                                originalBitmap,
                                0, 0,
                                originalBitmap.width, originalBitmap.height,
                                matrix,
                                true
                            )
                        }
                        
                        if (bitmap != null) {
                            lastPreviewImage = bitmap  // 更新预览图像
                            handLandmarkerDetector.detect(bitmap, System.currentTimeMillis())  // 执行识别
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "图像处理失败: ${e.message}")
                    } finally {
                        image.close()  // 确保释放图像资源
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "图像捕获失败: ${exception.message}")
                }
            }
        )
    }
    
    /**
     * 将ImageProxy转换为Bitmap
     * @param imageProxy 相机捕获的图像代理对象
     * @return 转换后的Bitmap，失败返回null
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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