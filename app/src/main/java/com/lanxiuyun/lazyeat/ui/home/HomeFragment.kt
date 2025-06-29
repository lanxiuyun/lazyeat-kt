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
import com.lanxiuyun.lazyeat.HandLandmarkerDetector
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
        
        // 初始化相机执行器
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // 初始化手势识别器
        initializeHandDetector()
        
        // 检查并请求相机权限
        if (allPermissionsGranted()) {
            startCamera()
        } else {
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
        handLandmarkerDetector = HandLandmarkerDetector(requireContext()) { result ->
            // 处理手势识别结果
            if (result != null) {
                val hands = result.landmarks()
                if (hands.isNotEmpty()) {
                    val hand = hands[0] // 获取第一只手的关键点
                    Log.d(TAG, "检测到手部关键点，数量: ${hand.size}")
                    
                    // 分析手势类型
                    val gesture = analyzeGesture(hand)
                    if (gesture.isNotEmpty()) {
                        updateGestureResult("识别到手势: $gesture")
                    }
                } else {
                    // 没有检测到手部
                    updateGestureResult("未检测到手部")
                }
            } else {
                // 识别失败
                updateGestureResult("识别失败")
            }
        }
        handLandmarkerDetector.initialize()
    }
    
    /**
     * 更新手势识别结果显示
     * @param result 识别结果文本
     */
    private fun updateGestureResult(result: String) {
        requireActivity().runOnUiThread {
            gestureResultText.text = result
            Toast.makeText(requireContext(), result, Toast.LENGTH_SHORT).show()
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
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 创建预览用例
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

            // 创建图像分析用例
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
                    viewLifecycleOwner, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
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
            try {
                // 获取图像数据
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                
                // 解码为 Bitmap
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                
                if (bitmap != null) {
                    // 处理图像旋转
                    val matrix = Matrix()
                    matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                    
                    // 如果是前置相机，需要水平翻转
                    if (imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270) {
                        matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                    }
                    
                    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ImageProxy 转 Bitmap 失败: ${e.message}")
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
    }
}