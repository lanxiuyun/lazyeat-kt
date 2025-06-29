package com.lanxiuyun.lazyeat.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.lanxiuyun.lazyeat.HandLandmarkerDetector
import com.lanxiuyun.lazyeat.HandOverlayView
import com.lanxiuyun.lazyeat.databinding.FragmentHomeBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 手势识别Fragment
 * 负责相机预览和手势识别功能
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    // 相机相关组件
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var handLandmarkerDetector: HandLandmarkerDetector
    private lateinit var gestureResultText: TextView
    private lateinit var handOverlayView: HandOverlayView
    
    // 权限相关常量
    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val TAG = "HomeFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel = ViewModelProvider(this).get(HomeViewModel::class.java)
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        
        // 初始化UI组件
        gestureResultText = binding.gestureResultText
        handOverlayView = binding.handOverlay
        
        // 设置初始状态
        updateGestureResult("正在初始化手势识别...")
        
        // 初始化相机执行器
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // 初始化手势识别器
        initializeHandDetector()
        
        // 检查并请求相机权限
        if (allPermissionsGranted()) {
            Log.d(TAG, "相机权限已获取，启动相机")
            startCamera()
        } else {
            Log.d(TAG, "请求相机权限")
            ActivityCompat.requestPermissions(
                requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
        
        return binding.root
    }
    
    /**
     * 初始化手势识别器
     */
    private fun initializeHandDetector() {
        Log.d(TAG, "开始初始化手势识别器")
        handLandmarkerDetector = HandLandmarkerDetector(requireContext()) { result ->
            // 处理手势识别结果
            if (result != null) {
                val hands = result.landmarks()
                Log.d(TAG, "收到手势识别结果，检测到 ${hands.size} 只手")
                
                if (hands.isNotEmpty()) {
                    val hand = hands[0] // 获取第一只手的关键点
                    Log.d(TAG, "第一只手的关键点数量: ${hand.size}")
                    
                    // 更新手部关键点显示
                    updateHandOverlay(result)
                    
                    // 分析手势类型
                    val gesture = analyzeGesture(hand)
                    if (gesture.isNotEmpty()) {
                        updateGestureResult("识别到手势: $gesture")
                        Log.d(TAG, "识别到手势: $gesture")
                    } else {
                        updateGestureResult("检测到手部，但无法识别手势类型")
                    }
                } else {
                    // 没有检测到手部
                    clearHandOverlay()
                    updateGestureResult("未检测到手部")
                    Log.d(TAG, "未检测到手部")
                }
            } else {
                // 识别失败
                clearHandOverlay()
                updateGestureResult("识别失败")
                Log.e(TAG, "手势识别失败")
            }
        }
        handLandmarkerDetector.initialize()
        Log.d(TAG, "手势识别器初始化完成")
    }
    
    /**
     * 更新手部关键点覆盖视图
     * @param result 手部识别结果
     */
    private fun updateHandOverlay(result: HandLandmarkerResult) {
        requireActivity().runOnUiThread {
            try {
                // 获取相机预览的尺寸
                val previewWidth = binding.cameraPreview.width
                val previewHeight = binding.cameraPreview.height
                
                Log.d(TAG, "更新手部覆盖视图，预览尺寸: ${previewWidth}x${previewHeight}")
                
                // 设置手部关键点显示
                handOverlayView.setResults(
                    result,
                    previewHeight,
                    previewWidth,
                    RunningMode.LIVE_STREAM
                )
            } catch (e: Exception) {
                Log.e(TAG, "更新手部覆盖视图失败: ${e.message}")
            }
        }
    }
    
    /**
     * 清除手部关键点显示
     */
    private fun clearHandOverlay() {
        requireActivity().runOnUiThread {
            try {
                handOverlayView.clear()
                Log.d(TAG, "清除手部覆盖视图")
            } catch (e: Exception) {
                Log.e(TAG, "清除手部覆盖视图失败: ${e.message}")
            }
        }
    }
    
    /**
     * 更新手势识别结果显示
     * @param result 识别结果文本
     */
    private fun updateGestureResult(result: String) {
        requireActivity().runOnUiThread {
            try {
                gestureResultText.text = result
                Log.d(TAG, "更新手势结果显示: $result")
            } catch (e: Exception) {
                Log.e(TAG, "更新手势结果显示失败: ${e.message}")
            }
        }
    }
    
    /**
     * 检查是否已获得所有必要权限
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 启动相机预览和图像分析
     */
    private fun startCamera() {
        Log.d(TAG, "开始启动相机")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 创建预览用例
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

            // 创建图像分析用例 - 使用RGBA_8888格式以匹配MediaPipe模型
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, HandAnalyzer())
                }

            // 使用前置相机
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview, imageAnalysis
                )
                Log.d(TAG, "相机启动成功")
                updateGestureResult("相机已启动，等待手势识别...")
            } catch (exc: Exception) {
                Log.e(TAG, "相机启动失败: ${exc.message}")
                updateGestureResult("相机启动失败: ${exc.message}")
                Toast.makeText(requireContext(), "相机启动失败", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
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
                } else {
                    Log.w(TAG, "图像转换失败，跳过此帧")
                }
            } catch (e: Exception) {
                Log.e(TAG, "图像分析失败: ${e.message}")
            } finally {
                imageProxy.close()
            }
        }

        /**
         * 将 ImageProxy 转换为 Bitmap
         * 参考MediaPipe官方CameraFragment的实现
         * @param imageProxy 相机图像代理
         * @return 转换后的 Bitmap，如果转换失败返回 null
         */
        private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
            try {
                Log.d(TAG, "开始转换图像，尺寸: ${imageProxy.width}x${imageProxy.height}, 格式: ${imageProxy.format}")
                
                // 创建ARGB_8888格式的Bitmap缓冲区
                val bitmapBuffer = Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
                
                // 从ImageProxy复制像素数据到Bitmap
                imageProxy.use { 
                    bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) 
                }
                
                // 创建变换矩阵
                val matrix = Matrix().apply {
                    // 根据相机旋转角度旋转图像
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                    
                    // 前置相机需要水平翻转
                    if (imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270) {
                        postScale(
                            -1f,
                            1f,
                            imageProxy.width.toFloat(),
                            imageProxy.height.toFloat()
                        )
                    }
                }
                
                // 应用变换并创建最终的Bitmap
                val rotatedBitmap = Bitmap.createBitmap(
                    bitmapBuffer, 
                    0, 0, 
                    bitmapBuffer.width, 
                    bitmapBuffer.height,
                    matrix, 
                    true
                )
                
                // 释放原始bitmap内存
                bitmapBuffer.recycle()
                
                Log.d(TAG, "图像转换成功，最终尺寸: ${rotatedBitmap.width}x${rotatedBitmap.height}")
                return rotatedBitmap
                
            } catch (e: Exception) {
                Log.e(TAG, "ImageProxy 转 Bitmap 失败: ${e.message}")
                e.printStackTrace()
            }
            
            return null
        }
    }

    /**
     * 分析手势类型
     * @param landmarks 手部关键点列表
     * @return 识别到的手势类型
     */
    private fun analyzeGesture(landmarks: List<NormalizedLandmark>): String {
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
            
            Log.d(TAG, "拇指和食指距离: $distance")
            
            return when {
                distance < 0.1 -> "捏合手势"
                distance > 0.3 -> "张开手势"
                else -> "其他手势"
            }
        }
        
        return ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 释放资源
        cameraExecutor.shutdown()
        handLandmarkerDetector.release()
        _binding = null
        Log.d(TAG, "HomeFragment 资源已释放")
    }
}