package com.lanxiuyun.lazyeat.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.lanxiuyun.lazyeat.HandOverlayView
import com.lanxiuyun.lazyeat.databinding.FragmentHomeBinding
import com.lanxiuyun.lazyeat.service.GestureRecognitionService
import com.lanxiuyun.lazyeat.service.ServiceManager

/**
 * 手势识别Fragment
 * 负责显示前台服务的手势识别结果
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    // UI组件
    private lateinit var gestureResultText: TextView
    private lateinit var handOverlayView: HandOverlayView
    private lateinit var startServiceButton: Button
    private lateinit var stopServiceButton: Button
    
    // 用于定期更新UI的Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateUIFromService()
            // 每100毫秒更新一次UI
            mainHandler.postDelayed(this, 100)
        }
    }
    
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
        updateGestureResult("等待启动手势识别服务...")
        
        // 检查并请求相机权限
        if (allPermissionsGranted()) {
            Log.i(TAG, "相机权限已获取")
            setupServiceControls()
        } else {
            Log.i(TAG, "请求相机权限")
            ActivityCompat.requestPermissions(
                requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
        
        return binding.root
    }
    
    /**
     * 设置服务控制按钮
     */
    private fun setupServiceControls() {
        // 启动服务按钮
        startServiceButton = binding.startServiceButton
        startServiceButton.setOnClickListener {
            startGestureRecognitionService()
        }
        
        // 停止服务按钮
        stopServiceButton = binding.stopServiceButton
        stopServiceButton.setOnClickListener {
            stopGestureRecognitionService()
        }
        
        // 更新按钮状态
        updateButtonStates()
    }
    
    /**
     * 启动手势识别服务
     */
    private fun startGestureRecognitionService() {
        try {
            Log.i(TAG, "启动手势识别服务")
            ServiceManager.startGestureRecognitionService(requireContext())
            updateGestureResult("正在启动手势识别服务...")
            updateButtonStates()
            Toast.makeText(requireContext(), "手势识别服务已启动", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败: ${e.message}")
            Toast.makeText(requireContext(), "启动服务失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 停止手势识别服务
     */
    private fun stopGestureRecognitionService() {
        try {
            Log.i(TAG, "停止手势识别服务")
            ServiceManager.stopGestureRecognitionService(requireContext())
            updateGestureResult("手势识别服务已停止")
            updateButtonStates()
            Toast.makeText(requireContext(), "手势识别服务已停止", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "停止服务失败: ${e.message}")
            Toast.makeText(requireContext(), "停止服务失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 更新按钮状态
     */
    private fun updateButtonStates() {
        val isServiceRunning = ServiceManager.isGestureRecognitionServiceRunning(requireContext())
        startServiceButton.isEnabled = !isServiceRunning
        stopServiceButton.isEnabled = isServiceRunning
    }
    
    /**
     * 从服务更新UI
     */
    private fun updateUIFromService() {
        try {
            // 更新手势识别结果文本
            val currentResult = GestureRecognitionService.currentGestureResult
            if (gestureResultText.text != currentResult) {
                gestureResultText.text = currentResult
            }
            
            // 更新手部关键点显示
            val lastResult = GestureRecognitionService.lastHandLandmarkerResult
            if (lastResult != null) {
                updateHandOverlay(lastResult)
            } else {
                clearHandOverlay()
            }
            
            // 更新按钮状态
            updateButtonStates()
            
        } catch (e: Exception) {
            Log.e(TAG, "更新UI失败: ${e.message}")
        }
    }
    
    /**
     * 更新手部关键点覆盖视图
     * @param result 手部识别结果
     */
    private fun updateHandOverlay(result: HandLandmarkerResult) {
        try {
            // 获取相机预览的尺寸（使用固定尺寸，因为服务中的摄像头不可见）
            val previewWidth = 640
            val previewHeight = 480
            
            // 设置手部关键点显示
            handOverlayView.setResults(
                result,
                previewHeight,
                previewWidth,
                com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM
            )
        } catch (e: Exception) {
            Log.e(TAG, "更新手部覆盖视图失败: ${e.message}")
        }
    }
    
    /**
     * 清除手部关键点显示
     */
    private fun clearHandOverlay() {
        try {
            handOverlayView.clear()
        } catch (e: Exception) {
            Log.e(TAG, "清除手部覆盖视图失败: ${e.message}")
        }
    }
    
    /**
     * 更新手势识别结果显示
     * @param result 识别结果文本
     */
    private fun updateGestureResult(result: String) {
        try {
            gestureResultText.text = result
        } catch (e: Exception) {
            Log.e(TAG, "更新手势结果显示失败: ${e.message}")
        }
    }
    
    /**
     * 检查是否已获得所有必要权限
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onResume() {
        super.onResume()
        // 开始定期更新UI
        mainHandler.post(updateRunnable)
    }
    
    override fun onPause() {
        super.onPause()
        // 停止定期更新UI
        mainHandler.removeCallbacks(updateRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 停止定期更新UI
        mainHandler.removeCallbacks(updateRunnable)
        _binding = null
        Log.i(TAG, "HomeFragment 资源已释放")
    }
}