package com.lanxiuyun.lazyeat.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
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
import kotlin.math.atan2

/**
 * 手势识别前台服务 - 吃饭刷抖音助手
 *
 * 这是一个持续运行的前台服务，用于实时手势识别并控制抖音滑动。
 * 主要功能包括：
 * 1. 摄像头管理：初始化和控制前置摄像头
 * 2. 手势识别：使用 MediaPipe 进行实时手部检测
 * 3. 方向判断：识别食指指向方向（向上/向下）
 * 4. 滑动触发：根据手势方向触发系统滑动事件
 * 5. 防抖机制：防止误触发
 * 6. 状态通知：在通知栏显示实时识别结果
 *
 * 技术特点：
 * - 使用 CameraX API 进行相机操作
 * - 采用 LifecycleService 确保正确的生命周期管理
 * - 实现前台服务确保持续运行
 * - 手势状态机管理（IDLE -> POINTING -> SWIPE -> COOLING）
 */
class GestureRecognitionService : LifecycleService() {

    // 相机相关组件
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var handLandmarkerDetector: HandLandmarkerDetector

    // 通知管理器
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    // 手势状态机
    private var gestureStateMachine = GestureStateMachine()

    // 手势识别参数（可从 SharedPreferences 读取）
    private var straightThreshold = 40f      // 伸直检测阈值
    private var bentThreshold = 45f        // 弯曲检测阈值（手指弯曲的最小角度）
    private var requireOtherFingersBent = true  // 是否要求其他手指弯曲

    companion object {
        private const val TAG = "GestureRecognitionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "gesture_recognition_channel"

        // 角度阈值常量（Android屏幕坐标系：y向下增加）
        // 手指向上指时 tip.y < pip.y，角度约 270°
        // 手指向下指时 tip.y > pip.y，角度约 90°
        const val UP_MIN_ANGLE = 240f          // 向上最小角度（对应屏幕下方，实际手指向上）
        const val UP_MAX_ANGLE = 300f          // 向上最大角度
        const val DOWN_MIN_ANGLE = 60f         // 向下最小角度（对应屏幕上方，实际手指向下）
        const val DOWN_MAX_ANGLE = 120f          // 向下最大角度

        // 与主界面共享的状态数据
        @Volatile
        var currentGestureResult: String = "等待手势识别..."
        @Volatile
        var lastHandLandmarkerResult: HandLandmarkerResult? = null
        @Volatile
        var lastPreviewImage: Bitmap? = null

        // 当前手势方向和状态（供UI显示）
        @Volatile
        var currentGestureDirection: GestureDirection = GestureDirection.NONE
        @Volatile
        var currentGestureState: GestureState = GestureState.IDLE

        fun setLogLevel(level: Int) {
            LogUtils.setLogLevel(level)
        }
    }

    /**
     * 手势方向枚举
     */
    enum class GestureDirection {
        NONE,   // 无明确方向
        UP,     // 向上指
        DOWN    // 向下指
    }

    /**
     * 手势状态枚举
     */
    enum class GestureState {
        IDLE,           // 空闲
        POINTING_UP,    // 向上指
        POINTING_DOWN,  // 向下指
        SWIPING_UP,     // 正在上滑
        SWIPING_DOWN,   // 正在下滑
        COOLING         // 冷却中
    }

    /**
     * 手势状态机 - 管理手势识别和触发逻辑
     */
    inner class GestureStateMachine {
        // 配置参数
        private val gestureHoldTime = 150L      // 手势保持时间（毫秒）- 越小越灵敏
        private val cooldownTime = 800L         // 冷却时间（毫秒）

        private var gestureStartTime: Long = 0
        private var lastTriggerTime: Long = 0
        private val debounceFrames = 3
        private val directionFrameCount = mutableMapOf<GestureDirection, Int>()

        fun process(direction: GestureDirection): GestureAction {
            val now = System.currentTimeMillis()

            // 防抖检查
            if (!debounce(direction)) return GestureAction.NONE

            return when (currentGestureState) {
                GestureState.IDLE -> handleIdleState(direction, now)
                GestureState.POINTING_UP -> handlePointingUpState(direction, now)
                GestureState.POINTING_DOWN -> handlePointingDownState(direction, now)
                GestureState.COOLING -> handleCoolingState(now)
                else -> GestureAction.NONE
            }
        }

        private fun handleIdleState(direction: GestureDirection, now: Long): GestureAction {
            return when (direction) {
                GestureDirection.UP -> {
                    currentGestureState = GestureState.POINTING_UP
                    gestureStartTime = now
                    Companion.currentGestureDirection = direction
                    LogUtils.d(TAG, "状态变更: IDLE -> POINTING_UP")
                    GestureAction.START_TRACKING
                }
                GestureDirection.DOWN -> {
                    currentGestureState = GestureState.POINTING_DOWN
                    gestureStartTime = now
                    Companion.currentGestureDirection = direction
                    LogUtils.d(TAG, "状态变更: IDLE -> POINTING_DOWN")
                    GestureAction.START_TRACKING
                }
                else -> GestureAction.NONE
            }
        }

        private fun handlePointingUpState(direction: GestureDirection, now: Long): GestureAction {
            return when (direction) {
                GestureDirection.UP -> {
                    val holdTime = now - gestureStartTime
                    if (holdTime > gestureHoldTime) {
                        currentGestureState = GestureState.SWIPING_UP
                        LogUtils.i(TAG, "触发上滑手势，保持时间: ${holdTime}ms")
                        GestureAction.TRIGGER_SWIPE_UP
                    } else {
                        GestureAction.CONTINUE_TRACKING
                    }
                }
                else -> {
                    currentGestureState = GestureState.IDLE
                    Companion.currentGestureDirection = GestureDirection.NONE
                    LogUtils.d(TAG, "手势中断，状态重置为 IDLE")
                    GestureAction.CANCEL_TRACKING
                }
            }
        }

        private fun handlePointingDownState(direction: GestureDirection, now: Long): GestureAction {
            return when (direction) {
                GestureDirection.DOWN -> {
                    val holdTime = now - gestureStartTime
                    if (holdTime > gestureHoldTime) {
                        currentGestureState = GestureState.SWIPING_DOWN
                        LogUtils.i(TAG, "触发下滑手势，保持时间: ${holdTime}ms")
                        GestureAction.TRIGGER_SWIPE_DOWN
                    } else {
                        GestureAction.CONTINUE_TRACKING
                    }
                }
                else -> {
                    currentGestureState = GestureState.IDLE
                    Companion.currentGestureDirection = GestureDirection.NONE
                    LogUtils.d(TAG, "手势中断，状态重置为 IDLE")
                    GestureAction.CANCEL_TRACKING
                }
            }
        }

        private fun handleCoolingState(now: Long): GestureAction {
            if (now - lastTriggerTime > cooldownTime) {
                currentGestureState = GestureState.IDLE
                Companion.currentGestureDirection = GestureDirection.NONE
                LogUtils.d(TAG, "冷却结束，状态重置为 IDLE")
            }
            return GestureAction.NONE
        }

        private fun debounce(direction: GestureDirection): Boolean {
            // 重置其他方向的计数
            directionFrameCount.keys.filter { it != direction }.forEach {
                directionFrameCount[it] = 0
            }

            // 增加当前方向的计数
            val count = (directionFrameCount[direction] ?: 0) + 1
            directionFrameCount[direction] = count

            return count >= debounceFrames
        }

        fun markSwipeCompleted() {
            currentGestureState = GestureState.COOLING
            lastTriggerTime = System.currentTimeMillis()
            Companion.currentGestureDirection = GestureDirection.NONE
            LogUtils.i(TAG, "滑动完成，进入冷却期")
        }
    }

    /**
     * 手势动作枚举
     */
    enum class GestureAction {
        NONE,               // 无动作
        START_TRACKING,   // 开始跟踪
        CONTINUE_TRACKING,  // 继续跟踪
        CANCEL_TRACKING,    // 取消跟踪
        TRIGGER_SWIPE_UP,   // 触发上滑
        TRIGGER_SWIPE_DOWN  // 触发下滑
    }

    override fun onCreate() {
        super.onCreate()
        LogUtils.i(TAG, "手势识别服务创建")

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 读取手势识别参数
        loadGestureParams()

        handLandmarkerDetector = HandLandmarkerDetector(this) { result ->
            handleGestureResult(result)
        }
        handLandmarkerDetector.initialize()

        createNotificationChannel()
    }

    /**
     * 从 SharedPreferences 加载手势识别参数
     */
    private fun loadGestureParams() {
        try {
            val prefs = getSharedPreferences("gesture_settings", Context.MODE_PRIVATE)
            straightThreshold = prefs.getFloat("straight_threshold", 40f)
            bentThreshold = prefs.getFloat("bent_threshold", 45f)
            requireOtherFingersBent = prefs.getBoolean("require_other_fingers_bent", true)
            LogUtils.i(TAG, "手势参数加载: 伸直阈值=${straightThreshold}°, 弯曲阈值=${bentThreshold}°, 需其他指弯曲=$requireOtherFingersBent")
        } catch (e: Exception) {
            LogUtils.w(TAG, "加载手势参数失败，使用默认值: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        LogUtils.i(TAG, "手势识别服务启动")

        startForeground(NOTIFICATION_ID, createNotification("手势识别服务运行中 - 等待手势"))
        startCamera()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtils.i(TAG, "手势识别服务销毁")

        cameraExecutor.shutdown()
        handLandmarkerDetector.release()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "手势识别服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示手势识别结果和当前状态"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("吃饭刷抖音助手")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build().apply {
                        setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

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

    private fun processImageProxy(image: ImageProxy) {
        try {
            val bitmap = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            ).apply {
                copyPixelsFromBuffer(image.planes[0].buffer)
            }

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
                postScale(-1f, 1f)
                postRotate(degrees.toFloat())
            }

            val processedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )

            updatePreviewImage(processedBitmap)
            handLandmarkerDetector.detect(processedBitmap, System.currentTimeMillis())

            bitmap.recycle()

        } catch (e: Exception) {
            LogUtils.e(TAG, "图像处理失败: ${e.message}")
        } finally {
            image.close()
        }
    }

    /**
     * 处理手势识别结果
     */
    private fun handleGestureResult(result: HandLandmarkerResult?) {
        updateHandLandmarkerResult(result)

        val gestureText = if (result != null && result.landmarks().isNotEmpty()) {
            val landmarks = result.landmarks().first()

            // 计算手指方向
            val direction = calculateFingerDirection(landmarks)
            Companion.currentGestureDirection = direction

            // 处理手势状态机
            val action = gestureStateMachine.process(direction)
            handleGestureAction(action)

            // 生成状态文本
            buildGestureStatusText(direction, result.landmarks().size)
        } else {
            // 没有检测到手，重置状态
            gestureStateMachine.process(GestureDirection.NONE)
            Companion.currentGestureDirection = GestureDirection.NONE
            "未检测到手部"
        }

        updateGestureResult(gestureText)
        updateNotification(gestureText)

        LogUtils.d(TAG, "手势识别结果: $gestureText")
    }

    /**
     * 计算食指指向方向
     * 使用 PIP(6) -> TIP(8) 两个关键点
     * 新增：检测食指是否伸直，并检查其他手指是否弯曲（握拳姿势）
     */
    private fun calculateFingerDirection(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): GestureDirection {
        if (landmarks.size < 21) return GestureDirection.NONE  // 需要所有手指的关键点

        val mcp = landmarks[5]   // 食指掌指关节
        val pip = landmarks[6]   // 食指近端指间关节
        val dip = landmarks[7]   // 食指远端指间关节
        val tip = landmarks[8]   // 食指指尖

        // 1. 检查食指是否伸直
        if (!isFingerStraight(mcp, pip, dip, tip)) {
            LogUtils.v(TAG, "食指未伸直，忽略手势")
            return GestureDirection.NONE
        }

        // 2. 检查其他手指是否弯曲（握拳姿势）
        if (requireOtherFingersBent && !areOtherFingersBent(landmarks)) {
            LogUtils.v(TAG, "其他手指未弯曲，忽略手势（未握拳）")
            return GestureDirection.NONE
        }

        // 使用 PIP -> TIP 向量计算方向（更稳定）
        val dx = tip.x() - pip.x()
        val dy = tip.y() - pip.y()

        // 计算角度（atan2 返回弧度，转换为度数）
        val angleRad = atan2(dy, dx)
        var angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

        // 归一化到 0-360
        if (angleDeg < 0) angleDeg += 360f

        LogUtils.v(TAG, "手指角度: ${"%.1f".format(angleDeg)}°")

        // 判断方向
        return when (angleDeg) {
            in UP_MIN_ANGLE..UP_MAX_ANGLE -> {
                LogUtils.d(TAG, "检测到向上手势 ☝️")
                GestureDirection.UP
            }
            in DOWN_MIN_ANGLE..DOWN_MAX_ANGLE -> {
                LogUtils.d(TAG, "检测到手势向下 👇")
                GestureDirection.DOWN
            }
            else -> GestureDirection.NONE
        }
    }

    /**
     * 检测单根手指是否伸直
     */
    private fun isFingerStraight(
        mcp: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        pip: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        dip: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        tip: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Boolean {
        val v1 = Pair(pip.x() - mcp.x(), pip.y() - mcp.y())
        val v2 = Pair(dip.x() - pip.x(), dip.y() - pip.y())
        val v3 = Pair(tip.x() - dip.x(), tip.y() - dip.y())

        val angle1 = Math.toDegrees(atan2(v1.second.toDouble(), v1.first.toDouble())).toFloat()
        val angle2 = Math.toDegrees(atan2(v2.second.toDouble(), v2.first.toDouble())).toFloat()
        val angle3 = Math.toDegrees(atan2(v3.second.toDouble(), v3.first.toDouble())).toFloat()

        fun normalizeAngle(angle: Float): Float {
            var result = angle
            while (result < 0) result += 360f
            while (result >= 360f) result -= 360f
            return result
        }

        fun angleDiff(a1: Float, a2: Float): Float {
            val diff = kotlin.math.abs(a1 - a2)
            return kotlin.math.min(diff, 360f - diff)
        }

        val normAngle1 = normalizeAngle(angle1)
        val normAngle2 = normalizeAngle(angle2)
        val normAngle3 = normalizeAngle(angle3)

        val diff12 = angleDiff(normAngle1, normAngle2)
        val diff23 = angleDiff(normAngle2, normAngle3)

        val isStraight = diff12 < straightThreshold && diff23 < straightThreshold

        if (!isStraight) {
            LogUtils.v(TAG, "手指弯曲检测: diff12=${"%.1f".format(diff12)}°, diff23=${"%.1f".format(diff23)}°, 阈值=$straightThreshold°")
        }

        return isStraight
    }

    /**
     * 检测其他手指（中指、无名指、小指）是否弯曲
     * 握拳姿势下，这些手指应该都是弯曲的
     */
    private fun areOtherFingersBent(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Boolean {
        // 中指 (9, 10, 11, 12)
        val middleBent = isFingerBent(landmarks[9], landmarks[10], landmarks[11], landmarks[12])
        // 无名指 (13, 14, 15, 16)
        val ringBent = isFingerBent(landmarks[13], landmarks[14], landmarks[15], landmarks[16])
        // 小指 (17, 18, 19, 20)
        val pinkyBent = isFingerBent(landmarks[17], landmarks[18], landmarks[19], landmarks[20])

        val allBent = middleBent && ringBent && pinkyBent

        if (!allBent) {
            LogUtils.v(TAG, "手指弯曲状态: 中指=$middleBent, 无名指=$ringBent, 小指=$pinkyBent")
        }

        return allBent
    }

    /**
     * 检测单根手指是否弯曲
     * 弯曲时，指尖与掌指关节的距离较近，或者各段角度差较大
     */
    private fun isFingerBent(
        mcp: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        pip: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        dip: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        tip: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Boolean {
        val v1 = Pair(pip.x() - mcp.x(), pip.y() - mcp.y())
        val v2 = Pair(dip.x() - pip.x(), dip.y() - pip.y())
        val v3 = Pair(tip.x() - dip.x(), tip.y() - dip.y())

        val angle1 = Math.toDegrees(atan2(v1.second.toDouble(), v1.first.toDouble())).toFloat()
        val angle2 = Math.toDegrees(atan2(v2.second.toDouble(), v2.first.toDouble())).toFloat()
        val angle3 = Math.toDegrees(atan2(v3.second.toDouble(), v3.first.toDouble())).toFloat()

        fun normalizeAngle(angle: Float): Float {
            var result = angle
            while (result < 0) result += 360f
            while (result >= 360f) result -= 360f
            return result
        }

        fun angleDiff(a1: Float, a2: Float): Float {
            val diff = kotlin.math.abs(a1 - a2)
            return kotlin.math.min(diff, 360f - diff)
        }

        val normAngle1 = normalizeAngle(angle1)
        val normAngle2 = normalizeAngle(angle2)
        val normAngle3 = normalizeAngle(angle3)

        val diff12 = angleDiff(normAngle1, normAngle2)
        val diff23 = angleDiff(normAngle2, normAngle3)

        // 手指弯曲的判断：各段角度差大于弯曲阈值
        return diff12 > bentThreshold || diff23 > bentThreshold
    }

    /**
     * 处理手势动作
     * 注意：抖音的滑动逻辑与直觉相反
     * - 向上滑动（从下到上）→ 切换到下一个视频
     * - 向下滑动（从上到下）→ 切换到上一个视频
     * 因此手指方向与滑动方向相反：
     * - 手指向下指 👇 → 向上滑动（下一个视频）
     * - 手指向上指 ☝️ → 向下滑动（上一个视频）
     */
    private fun handleGestureAction(action: GestureAction) {
        when (action) {
            GestureAction.TRIGGER_SWIPE_UP -> {
                // 手指向上指 ☝️ → 向下滑动 → 上一个视频
                performSwipeDown()
                gestureStateMachine.markSwipeCompleted()
            }
            GestureAction.TRIGGER_SWIPE_DOWN -> {
                // 手指向下指 👇 → 向上滑动 → 下一个视频
                performSwipeUp()
                gestureStateMachine.markSwipeCompleted()
            }
            else -> { /* 其他动作无需处理 */ }
        }
    }

    /**
     * 执行向上滑动（从屏幕下方向上滑）- 切换到下一个视频
     * 对应手指向下指的手势
     */
    private fun performSwipeUp() {
        LogUtils.i(TAG, "执行向上滑动 👇 → 下一个视频")

        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2
        val centerY = displayMetrics.heightPixels / 2
        val swipeDistance = 800  // 滑动距离

        // 发送广播给辅助功能服务（如果已开启）
        val intent = Intent("com.lanxiuyun.lazyeat.SWIPE_GESTURE").apply {
            setPackage(packageName)
            putExtra("direction", "up")
            putExtra("distance", swipeDistance)
            putExtra("duration", 300L)
        }
        sendBroadcast(intent)
        LogUtils.d(TAG, "已发送上滑广播")

        // 尝试使用 InputManager 直接注入事件（需要系统签名或 root）
        tryInjectSwipe(centerX, centerY, -swipeDistance)

        // 更新状态
        updateGestureResult("向上滑动 👇 → 下一个视频")
    }

    /**
     * 执行向下滑动（从屏幕上方往下滑）- 切换到上一个视频
     * 对应手指向上指的手势
     */
    private fun performSwipeDown() {
        LogUtils.i(TAG, "执行向下滑动 ☝️ → 上一个视频")

        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2
        val centerY = displayMetrics.heightPixels / 2
        val swipeDistance = 800  // 滑动距离

        // 发送广播给辅助功能服务
        val intent = Intent("com.lanxiuyun.lazyeat.SWIPE_GESTURE").apply {
            setPackage(packageName)
            putExtra("direction", "down")
            putExtra("distance", swipeDistance)
            putExtra("duration", 300L)
        }
        sendBroadcast(intent)
        LogUtils.d(TAG, "已发送下滑广播")

        // 尝试直接注入
        tryInjectSwipe(centerX, centerY, swipeDistance)

        // 更新状态
        updateGestureResult("向下滑动 ☝️ → 上一个视频")
    }

    /**
     * 尝试直接注入滑动事件（通过反射访问 InputManager）
     * 注意：此功能需要系统签名或 root 权限才能正常工作
     */
    private fun tryInjectSwipe(centerX: Int, centerY: Int, distance: Int) {
        try {
            // 获取 InputManager 实例
            val inputManagerClass = Class.forName("android.hardware.input.InputManager")
            val getInstanceMethod = inputManagerClass.getDeclaredMethod("getInstance")
            val inputManager = getInstanceMethod.invoke(null)

            // 获取 injectInputEvent 方法
            val injectMethod = inputManagerClass.getDeclaredMethod(
                "injectInputEvent",
                Class.forName("android.view.InputEvent"),
                Int::class.javaPrimitiveType
            )

            // 创建 MotionEvent（Down）
            val motionEventClass = Class.forName("android.view.MotionEvent")
            val obtainMethod = motionEventClass.getDeclaredMethod(
                "obtain",
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            val downTime = SystemClock.uptimeMillis()

            // 发送 ACTION_DOWN
            val downEvent = obtainMethod.invoke(
                null,
                downTime, downTime, 0,  // ACTION_DOWN
                centerX.toFloat(), (centerY + distance / 2).toFloat(),
                1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0
            )
            injectMethod.invoke(inputManager, downEvent, 0)
            LogUtils.d(TAG, "注入 ACTION_DOWN 事件")

            // 发送 ACTION_MOVE（中间点）
            val moveEvent = obtainMethod.invoke(
                null,
                downTime, downTime + 100, 2,  // ACTION_MOVE
                centerX.toFloat(), centerY.toFloat(),
                1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0
            )
            injectMethod.invoke(inputManager, moveEvent, 0)

            // 发送 ACTION_UP
            val upEvent = obtainMethod.invoke(
                null,
                downTime, downTime + 300, 1,  // ACTION_UP
                centerX.toFloat(), (centerY - distance / 2).toFloat(),
                1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0
            )
            injectMethod.invoke(inputManager, upEvent, 0)
            LogUtils.d(TAG, "注入 ACTION_UP 事件")

            LogUtils.i(TAG, "直接注入滑动事件成功")
        } catch (e: Exception) {
            LogUtils.w(TAG, "直接注入滑动事件失败（需要系统权限或 root）: ${e.message}")
            // 备用方案：尝试使用 shell 命令
            tryShellSwipe(centerX, centerY, distance)
        }
    }

    /**
     * 使用 shell 命令模拟滑动（需要 root）
     */
    private fun tryShellSwipe(centerX: Int, centerY: Int, distance: Int) {
        try {
            val startY = centerY + distance / 2
            val endY = centerY - distance / 2
            val command = "input swipe $centerX $startY $centerX $endY 300"

            val process = Runtime.getRuntime().exec(command)
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                LogUtils.i(TAG, "Shell 滑动命令执行成功")
            } else {
                LogUtils.w(TAG, "Shell 滑动命令执行失败，exitCode: $exitCode")
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Shell 滑动命令异常: ${e.message}")
        }
    }

    /**
     * 构建手势状态文本
     */
    private fun buildGestureStatusText(direction: GestureDirection, handCount: Int): String {
        val directionText = when (direction) {
            GestureDirection.UP -> "☝️ 向上"
            GestureDirection.DOWN -> "👇 向下"
            GestureDirection.NONE -> "🤚 无方向"
        }

        val stateText = when (Companion.currentGestureState) {
            GestureState.IDLE -> "等待中"
            GestureState.POINTING_UP -> "向上保持"
            GestureState.POINTING_DOWN -> "向下保持"
            GestureState.SWIPING_UP -> "触发上滑！"
            GestureState.SWIPING_DOWN -> "触发下滑！"
            GestureState.COOLING -> "冷却中..."
        }

        return "$directionText | $stateText | 检测到 $handCount 只手"
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateGestureResult(result: String) {
        currentGestureResult = result
    }

    private fun updateHandLandmarkerResult(result: HandLandmarkerResult?) {
        lastHandLandmarkerResult = result
        if (result != null) {
            LogUtils.v(TAG, "检测到 ${result.handednesses().size} 只手")
        }
    }

    private fun updatePreviewImage(image: Bitmap?) {
        lastPreviewImage = image
        if (image != null) {
            LogUtils.v(TAG, "更新预览图像: ${image.width}x${image.height}")
        }
    }
}
