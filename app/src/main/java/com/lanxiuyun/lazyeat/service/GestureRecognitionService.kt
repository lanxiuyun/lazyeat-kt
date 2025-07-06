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
 * 负责：
 * 1. 启动摄像头捕获
 * 2. 进行手势识别
 * 3. 显示通知结果
 * 4. 与主界面通信
 */
class GestureRecognitionService : LifecycleService() {
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var handLandmarkerDetector: HandLandmarkerDetector
    
    // 通知相关
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }
    
    companion object {
        private const val TAG = "GestureRecognitionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "gesture_recognition_channel"
        
        // 用于与主界面通信的静态变量
        @Volatile
        var currentGestureResult: String = "等待手势识别..."
        @Volatile
        var lastHandLandmarkerResult: HandLandmarkerResult? = null
        @Volatile
        var lastPreviewImage: Bitmap? = null
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "手势识别服务创建")
        
        // 初始化摄像头执行器
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // 初始化手势识别器
        handLandmarkerDetector = HandLandmarkerDetector(this) { result ->
            handleGestureResult(result)
        }
        handLandmarkerDetector.initialize()
        
        // 创建通知渠道
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "手势识别服务启动")
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("手势识别服务运行中"))
        
        // 启动摄像头
        startCamera()
        
        return START_STICKY // 服务被杀死后自动重启
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "手势识别服务销毁")
        
        // 释放资源
        cameraExecutor.shutdown()
        handLandmarkerDetector.release()
    }
    
    /**
     * 创建通知渠道（Android 8.0+ 必需）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "手势识别服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示手势识别结果"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手势识别")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    /**
     * 启动摄像头
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // 配置图像捕获用例
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    // 设置目标旋转，这样输出的图像就是正确方向
                    .setTargetRotation(getDisplayRotation())
                    // 设置镜像模式，前置摄像头需要水平镜像
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)  // 4:3 比例约等于 640x480
                    .build()
                
                // 选择前置摄像头
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()
                
                // 绑定用例到生命周期
                camera = cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageCapture
                )
                
                this.imageCapture = imageCapture
                
                // 开始定期捕获图像进行手势识别
                startPeriodicImageCapture()
                
                Log.i(TAG, "摄像头启动成功")
                
            } catch (e: Exception) {
                Log.e(TAG, "摄像头启动失败: ${e.message}")
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    /**
     * 获取屏幕旋转角度（返回 Surface.ROTATION_* 常量）。
     * 对于前台服务没有可视窗口的情况，若获取失败则返回 ROTATION_0。
     */
    private fun getDisplayRotation(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 尝试通过 Context 的 display 属性获取旋转信息
                this.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            }
        } catch (e: Exception) {
            Log.w(TAG, "无法获取 Display rotation，使用默认 ROTATION_0: ${e.message}")
            Surface.ROTATION_0
        }
    }
    
    /**
     * 开始定期捕获图像
     */
    private fun startPeriodicImageCapture() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        val captureRunnable = object : Runnable {
            override fun run() {
                captureImageForGestureRecognition()
                // 每500毫秒捕获一次图像
                handler.postDelayed(this, 500)
            }
        }
        
        handler.post(captureRunnable)
    }
    
    /**
     * 捕获图像进行手势识别
     */
    private fun captureImageForGestureRecognition() {
        val imageCapture = imageCapture ?: return
        
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        // 将ImageProxy转换为Bitmap，并进行镜像处理
                        val bitmap = imageProxyToBitmap(image)?.let { originalBitmap ->
                            // 创建镜像矩阵
                            val matrix = Matrix().apply {
                                // 水平镜像
                                postScale(-1f, 1f, originalBitmap.width / 2f, originalBitmap.height / 2f)
                            }
                            
                            // 创建新的镜像Bitmap
                            Bitmap.createBitmap(
                                originalBitmap,
                                0, 0,
                                originalBitmap.width, originalBitmap.height,
                                matrix,
                                true
                            )
                        }
                        
                        if (bitmap != null) {
                            // 更新预览图像
                            lastPreviewImage = bitmap
                            // 进行手势识别
                            handLandmarkerDetector.detect(bitmap, System.currentTimeMillis())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "图像处理失败: ${e.message}")
                    } finally {
                        image.close()
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
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    
    /**
     * 处理手势识别结果
     */
    private fun handleGestureResult(result: HandLandmarkerResult?) {
        lastHandLandmarkerResult = result
        
        val gestureText = if (result != null && result.landmarks().isNotEmpty()) {
            // 这里可以添加更复杂的手势识别逻辑
            val handCount = result.landmarks().size
            "检测到 $handCount 只手"
        } else {
            "未检测到手部"
        }
        
        currentGestureResult = gestureText
        
        // 更新通知
        updateNotification(gestureText)
        
        Log.d(TAG, "手势识别结果: $gestureText")
    }
    
    /**
     * 更新通知内容
     */
    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
} 