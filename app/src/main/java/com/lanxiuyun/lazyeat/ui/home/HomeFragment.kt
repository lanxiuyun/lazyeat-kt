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
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.lanxiuyun.lazyeat.HandOverlayView
import com.lanxiuyun.lazyeat.R
import com.lanxiuyun.lazyeat.databinding.FragmentHomeBinding
import com.lanxiuyun.lazyeat.service.GestureRecognitionService
import com.lanxiuyun.lazyeat.service.ServiceManager
import com.lanxiuyun.lazyeat.utils.LogUtils

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
    private lateinit var logLevelSpinner: Spinner
    
    // 灵敏度调节组件
    private lateinit var straightThresholdSeekBar: SeekBar
    private lateinit var bentThresholdSeekBar: SeekBar
    private lateinit var requireFistSwitch: Switch
    private lateinit var straightThresholdValue: TextView
    private lateinit var bentThresholdValue: TextView
    
    // 手势参数
    private var currentStraightThreshold = 40f
    private var currentBentThreshold = 45f
    private var currentRequireFist = true
    
    // 用于定期更新UI的Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateUIFromService()
            // 每20毫秒更新一次UI
            mainHandler.postDelayed(this,20)
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
        logLevelSpinner = binding.logLevelSpinner
        
        // 初始化灵敏度调节组件
        initSensitivityControls()
        
        // 设置日志等级选择器
        setupLogLevelSpinner()
        
        // 设置初始状态
        updateGestureResult("等待启动手势识别服务...")
        
        // 检查并请求相机权限
        if (allPermissionsGranted()) {
            LogUtils.i(TAG, "相机权限已获取")
            setupServiceControls()
        } else {
            LogUtils.i(TAG, "请求相机权限")
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
            LogUtils.i(TAG, "启动手势识别服务")
            ServiceManager.startGestureRecognitionService(requireContext())
            updateGestureResult("正在启动手势识别服务...")
            updateButtonStates()
            Toast.makeText(requireContext(), "手势识别服务已启动", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LogUtils.e(TAG, "启动服务失败: ${e.message}")
            Toast.makeText(requireContext(), "启动服务失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 停止手势识别服务
     */
    private fun stopGestureRecognitionService() {
        try {
            LogUtils.i(TAG, "停止手势识别服务")
            ServiceManager.stopGestureRecognitionService(requireContext())
            updateGestureResult("手势识别服务已停止")
            updateButtonStates()
            Toast.makeText(requireContext(), "手势识别服务已停止", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LogUtils.e(TAG, "停止服务失败: ${e.message}")
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

            // 更新预览图像和手部关键点显示
            val lastImage = GestureRecognitionService.lastPreviewImage
            val lastResult = GestureRecognitionService.lastHandLandmarkerResult

            if (lastImage != null) {
                handOverlayView.setPreviewImage(lastImage)

                if (lastResult != null) {
                    handOverlayView.setResults(
                        lastResult,
                        lastImage.height,
                        lastImage.width,
                        com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM
                    )
                }

                // 更新手势方向显示
                handOverlayView.setGestureInfo(
                    GestureRecognitionService.currentGestureDirection,
                    GestureRecognitionService.currentGestureState
                )
            } else {
                clearHandOverlay()
            }

            // 更新按钮状态
            updateButtonStates()

        } catch (e: Exception) {
            LogUtils.e(TAG, "更新UI失败: ${e.message}")
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
            LogUtils.e(TAG, "更新手部覆盖视图失败: ${e.message}")
        }
    }
    
    /**
     * 清除手部关键点显示
     */
    private fun clearHandOverlay() {
        try {
            handOverlayView.clear()
        } catch (e: Exception) {
            LogUtils.e(TAG, "清除手部覆盖视图失败: ${e.message}")
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
            LogUtils.e(TAG, "更新手势结果显示失败: ${e.message}")
        }
    }
    
    /**
     * 检查是否已获得所有必要权限
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 设置日志等级选择器
     */
    private fun setupLogLevelSpinner() {
        // 创建适配器
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.log_levels,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            // 指定下拉列表的布局样式
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            // 将适配器应用到spinner
            logLevelSpinner.adapter = adapter
            
            // 设置默认选择为WARN
            val defaultPosition = adapter.getPosition("WARN")
            if (defaultPosition != -1) {
                logLevelSpinner.setSelection(defaultPosition)
            }
        }

        // 设置选择监听器
        logLevelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val selectedLevel = parent.getItemAtPosition(pos).toString()
                updateLogLevel(selectedLevel)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // 不做任何处理
            }
        }
    }

    /**
     * 更新日志等级
     */
    private fun updateLogLevel(level: String) {
        val logLevel = when (level) {
            "VERBOSE" -> Log.VERBOSE
            "DEBUG" -> Log.DEBUG
            "INFO" -> Log.INFO
            "WARN" -> Log.WARN
            "ERROR" -> Log.ERROR
            else -> Log.INFO
        }
        
        // 更新日志等级
        try {
            GestureRecognitionService.setLogLevel(logLevel)
            LogUtils.i(TAG, "日志等级已更新为: $level")
            Toast.makeText(requireContext(), "日志等级已更新为: $level", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LogUtils.e(TAG, "更新日志等级失败: ${e.message}")
            Toast.makeText(requireContext(), "更新日志等级失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 初始化灵敏度调节控件
     */
    private fun initSensitivityControls() {
        // 加载保存的参数
        loadGestureParams()
        
        // 初始化组件引用
        straightThresholdSeekBar = binding.straightThresholdSeekbar
        bentThresholdSeekBar = binding.bentThresholdSeekbar
        requireFistSwitch = binding.requireFistSwitch
        straightThresholdValue = binding.straightThresholdValue
        bentThresholdValue = binding.bentThresholdValue
        
        // 设置初始值
        straightThresholdSeekBar.progress = currentStraightThreshold.toInt()
        bentThresholdSeekBar.progress = currentBentThreshold.toInt()
        requireFistSwitch.isChecked = currentRequireFist
        updateThresholdLabels()
        
        // 伸直阈值 SeekBar
        straightThresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentStraightThreshold = progress.toFloat()
                updateThresholdLabels()
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveGestureParams()
                Toast.makeText(requireContext(), "伸直严格度已调整为 ${currentStraightThreshold.toInt()}°", Toast.LENGTH_SHORT).show()
            }
        })
        
        // 弯曲阈值 SeekBar
        bentThresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentBentThreshold = progress.toFloat()
                updateThresholdLabels()
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveGestureParams()
                Toast.makeText(requireContext(), "握拳严格度已调整为 ${currentBentThreshold.toInt()}°", Toast.LENGTH_SHORT).show()
            }
        })
        
        // 握拳开关
        requireFistSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentRequireFist = isChecked
            saveGestureParams()
            val message = if (isChecked) "已启用握拳检测（其他手指必须弯曲）" else "已禁用握拳检测（单手食指即可触发）"
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 更新阈值显示标签
     */
    private fun updateThresholdLabels() {
        straightThresholdValue.text = "${currentStraightThreshold.toInt()}°"
        bentThresholdValue.text = "${currentBentThreshold.toInt()}°"
    }
    
    /**
     * 从 SharedPreferences 加载手势参数
     */
    private fun loadGestureParams() {
        try {
            val prefs = requireContext().getSharedPreferences("gesture_settings", android.content.Context.MODE_PRIVATE)
            currentStraightThreshold = prefs.getFloat("straight_threshold", 40f)
            currentBentThreshold = prefs.getFloat("bent_threshold", 45f)
            currentRequireFist = prefs.getBoolean("require_other_fingers_bent", true)
            LogUtils.i(TAG, "手势参数已加载: 伸直=${currentStraightThreshold}°, 弯曲=${currentBentThreshold}°, 需握拳=$currentRequireFist")
        } catch (e: Exception) {
            LogUtils.w(TAG, "加载手势参数失败: ${e.message}")
        }
    }
    
    /**
     * 保存手势参数到 SharedPreferences
     */
    private fun saveGestureParams() {
        try {
            val prefs = requireContext().getSharedPreferences("gesture_settings", android.content.Context.MODE_PRIVATE)
            prefs.edit().apply {
                putFloat("straight_threshold", currentStraightThreshold)
                putFloat("bent_threshold", currentBentThreshold)
                putBoolean("require_other_fingers_bent", currentRequireFist)
                apply()
            }
            LogUtils.d(TAG, "手势参数已保存: 伸直=${currentStraightThreshold}°, 弯曲=${currentBentThreshold}°, 需握拳=$currentRequireFist")
        } catch (e: Exception) {
            LogUtils.e(TAG, "保存手势参数失败: ${e.message}")
        }
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
        LogUtils.i(TAG, "HomeFragment 资源已释放")
    }
}