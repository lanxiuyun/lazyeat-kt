package com.lanxiuyun.lazyeat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lanxiuyun.lazyeat.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var handLandmarkerDetector: HandLandmarkerDetector

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        // 初始化相机
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 初始化手势识别器
        handLandmarkerDetector = HandLandmarkerDetector(this) { result ->
            // 处理手势识别结果
            if (result != null) {
                val hands = result.landmarks()
                if (hands.isNotEmpty()) {
                    val hand = hands[0] // 获取第一只手的关键点
                    Log.d(TAG, "检测到手部关键点，数量: ${hand.size}")
                    
                    // 这里可以根据关键点位置判断手势
                    // 例如：检查拇指和食指的位置来判断手势类型
                    val gesture = analyzeGesture(hand)
                    if (gesture.isNotEmpty()) {
                        runOnUiThread {
                            Toast.makeText(this, "识别到手势: $gesture", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        handLandmarkerDetector.initialize()

        // 请求相机权限
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限才能使用此功能", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

            // 创建图像分析器
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, HandAnalyzer())
                }

            // 使用前置相机
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 图像分析器，用于处理相机帧并进行手势识别
     */
    private inner class HandAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            try {
                // 将 ImageProxy 转换为 Bitmap
                val bitmap = imageProxyToBitmap(imageProxy)
                if (bitmap != null) {
                    // 进行手势识别
                    handLandmarkerDetector.detect(bitmap, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                Log.e(TAG, "图像分析失败: ${e.message}")
            } finally {
                imageProxy.close()
            }
        }

        /**
         * 将 ImageProxy 转换为 Bitmap
         * @param imageProxy 相机图像代理
         * @return 转换后的 Bitmap，如果转换失败返回 null
         */
        private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            
            // 如果是前置相机，需要水平翻转图像
            if (imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270) {
                val matrix = Matrix()
                matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            
            return bitmap
        }
    }

    /**
     * 分析手势类型
     * @param landmarks 手部关键点列表
     * @return 识别到的手势类型
     */
    private fun analyzeGesture(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): String {
        // 这里实现简单的手势识别逻辑
        // 可以根据关键点的相对位置来判断手势类型
        
        if (landmarks.size >= 21) { // MediaPipe 手部模型有 21 个关键点
            // 获取拇指尖和食指尖的位置
            val thumbTip = landmarks[4] // 拇指尖
            val indexTip = landmarks[8] // 食指尖
            
            // 简单的距离判断
            val distance = Math.sqrt(
                Math.pow((thumbTip.x() - indexTip.x()).toDouble(), 2.0) +
                Math.pow((thumbTip.y() - indexTip.y()).toDouble(), 2.0)
            )
            
            return when {
                distance < 0.1 -> "捏合手势"
                distance > 0.3 -> "张开手势"
                else -> "其他手势"
            }
        }
        
        return ""
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handLandmarkerDetector.release()
    }
}