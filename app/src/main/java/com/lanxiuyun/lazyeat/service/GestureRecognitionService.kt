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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.lanxiuyun.lazyeat.HandLandmarkerDetector
import com.lanxiuyun.lazyeat.MainActivity
import com.lanxiuyun.lazyeat.R
import com.lanxiuyun.lazyeat.utils.LogUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 手势识别前台服务 - 吃饭刷抖音助手
 *
 * 手势识别流程优化版本：
 * 1. 多条件方向判定：结合 MCP->TIP 和 PIP->TIP 向量，增加手指伸直度检测
 * 2. 时序平滑机制：EMA平滑 + 迟滞阈值 + 稳定度积分
 * 3. 手势质量评估：食指伸直、其他手指收拢、手部稳定性
 * 4. 积分式状态机：证据分数累积替代简单帧计数
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

    // 手势识别组件
    private lateinit var gestureAnalyzer: GestureAnalyzer
    private lateinit var gestureStateMachine: RobustGestureStateMachine

    companion object {
        private const val TAG = "GestureRecognitionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "gesture_recognition_channel"

        // ========== 方向角度阈值配置（带迟滞） ==========
        // Android屏幕坐标系：y向下增加
        // 手指向上指时 tip.y < pip.y，角度约 270°
        // 手指向下指时 tip.y > pip.y，角度约 90°

        // 进入阈值（进入对应方向需要更严格）
        const val UP_ENTER_MIN = 250f
        const val UP_ENTER_MAX = 290f
        const val DOWN_ENTER_MIN = 70f
        const val DOWN_ENTER_MAX = 110f

        // 退出阈值（退出方向可以更宽松，提供迟滞）
        const val UP_EXIT_MIN = 230f
        const val UP_EXIT_MAX = 310f
        const val DOWN_EXIT_MIN = 50f
        const val DOWN_EXIT_MAX = 130f

        // ========== 手指姿态质量阈值 ==========
        const val INDEX_STRAIGHTNESS_THRESHOLD = 0.85f  // 食指伸直度阈值
        const val OTHER_FOLDED_THRESHOLD = 0.3f         // 其他手指收拢阈值（弯曲度）
        const val HAND_MIN_SIZE = 0.15f                 // 手部最小尺寸（相对图像）
        const val HAND_EDGE_MARGIN = 0.1f               // 边缘距离阈值

        // ========== 时序平滑配置 ==========
        const val EMA_ALPHA = 0.3f                      // EMA平滑系数（越小越平滑）
        const val DIRECTION_BUFFER_SIZE = 7             // 方向缓冲窗口大小
        const val STABLE_DIRECTION_THRESHOLD = 0.7f     // 稳定方向比例阈值

        // ========== 积分式状态机配置 ==========
        const val CONFIRMATION_THRESHOLD = 100           // 确认手势所需积分
        const val EVIDENCE_PER_FRAME = 20              // 每帧稳定方向提供的证据
        const val NOISE_PENALTY = 30                   // 噪声帧扣除的证据
        const val GESTURE_HOLD_TIME = 300L             // 手势保持时间（毫秒）- 保守设置
        const val COOLDOWN_TIME = 1000L                // 冷却时间（毫秒）
        const val MAX_LOST_FRAMES = 5                  // 允许丢失的最大帧数

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

        // 手势质量信息（供调试用）
        @Volatile
        var gestureQualityInfo: String = ""

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
     * 手势状态枚举 - 使用更细化的状态
     */
    enum class GestureState {
        IDLE,               // 空闲
        CANDIDATE_UP,       // 向上候选
        CANDIDATE_DOWN,     // 向下候选
        CONFIRMED_UP,       // 向上确认
        CONFIRMED_DOWN,     // 向下确认
        SWIPING_UP,         // 正在上滑
        SWIPING_DOWN,       // 正在下滑
        COOLING             // 冷却中
    }

    /**
     * 手势质量评估结果
     */
    data class GestureQuality(
        val indexStraightness: Float,      // 食指伸直度 (0-1)
        val otherFingersFolded: Boolean,   // 其他手指是否收拢
        val handSize: Float,               // 手部尺寸
        val handCentered: Boolean,         // 手部是否居中
        val isStable: Boolean,             // 是否稳定
        val confidence: Float              // 综合置信度 (0-1)
    ) {
        fun isValid(): Boolean = confidence > 0.6f
    }

    /**
     * 手势分析器 - 负责方向计算和质量评估
     */
    inner class GestureAnalyzer {
        // EMA平滑后的角度
        private var smoothedAngle: Float = -1f

        // 方向历史缓冲区
        private val directionBuffer = ArrayDeque<GestureDirection>(DIRECTION_BUFFER_SIZE)

        // 当前确认的方向（带迟滞）
        private var confirmedDirection: GestureDirection = GestureDirection.NONE

        /**
         * 分析手势，返回方向和质量
         */
        fun analyze(landmarks: List<NormalizedLandmark>): Pair<GestureDirection, GestureQuality> {
            if (landmarks.size < 21) {
                return GestureDirection.NONE to GestureQuality(0f, false, 0f, false, false, 0f)
            }

            // 1. 计算手指方向（使用 MCP->TIP 和 PIP->TIP 组合）
            val rawAngle = calculateCombinedAngle(landmarks)
            if (rawAngle < 0) {
                return GestureDirection.NONE to GestureQuality(0f, false, 0f, false, false, 0f)
            }

            // 2. EMA平滑
            smoothedAngle = if (smoothedAngle < 0) rawAngle
            else EMA_ALPHA * rawAngle + (1 - EMA_ALPHA) * smoothedAngle

            // 3. 评估手指姿态质量
            val quality = assessGestureQuality(landmarks, smoothedAngle)

            // 4. 使用迟滞判断方向
            val direction = determineDirectionWithHysteresis(smoothedAngle, quality)

            // 5. 更新方向缓冲区并计算稳定性
            val stableDirection = calculateStableDirection(direction)

            return stableDirection to quality
        }

        /**
         * 计算组合角度（使用 MCP->TIP 和 PIP->TIP 加权）
         */
        private fun calculateCombinedAngle(landmarks: List<NormalizedLandmark>): Float {
            val mcp = landmarks[5]  // 食指掌指关节
            val pip = landmarks[6]  // 食指近端指间关节
            val tip = landmarks[8]  // 食指指尖

            // 向量1: PIP -> TIP（远端，更敏感）
            val dx1 = tip.x() - pip.x()
            val dy1 = tip.y() - pip.y()

            // 向量2: MCP -> TIP（整体，更稳定）
            val dx2 = tip.x() - mcp.x()
            val dy2 = tip.y() - mcp.y()

            // 加权组合（远端0.6，整体0.4）
            val dx = 0.6f * dx1 + 0.4f * dx2
            val dy = 0.6f * dy1 + 0.4f * dy2

            val angleRad = atan2(dy, dx)
            var angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
            if (angleDeg < 0) angleDeg += 360f

            return angleDeg
        }

        /**
         * 评估手势质量
         */
        private fun assessGestureQuality(
            landmarks: List<NormalizedLandmark>,
            angle: Float
        ): GestureQuality {
            // 1. 食指伸直度
            val straightness = calculateIndexStraightness(landmarks)

            // 2. 其他手指是否收拢
            val othersFolded = areOtherFingersFolded(landmarks)

            // 3. 手部尺寸
            val handSize = calculateHandSize(landmarks)

            // 4. 手部是否居中
            val centered = isHandCentered(landmarks)

            // 5. 综合置信度
            val confidence = calculateConfidence(straightness, othersFolded, handSize, centered, angle)

            return GestureQuality(
                indexStraightness = straightness,
                otherFingersFolded = othersFolded,
                handSize = handSize,
                handCentered = centered,
                isStable = straightness > 0.8f && othersFolded,
                confidence = confidence
            )
        }

        /**
         * 计算食指伸直度
         */
        private fun calculateIndexStraightness(landmarks: List<NormalizedLandmark>): Float {
            val mcp = landmarks[5]
            val pip = landmarks[6]
            val dip = landmarks[7]
            val tip = landmarks[8]

            // 向量 MCP->PIP 和 PIP->DIP 和 DIP->TIP 应该大致同向
            val v1x = pip.x() - mcp.x()
            val v1y = pip.y() - mcp.y()
            val v2x = dip.x() - pip.x()
            val v2y = dip.y() - pip.y()
            val v3x = tip.x() - dip.x()
            val v3y = tip.y() - dip.y()

            // 归一化
            val len1 = sqrt(v1x * v1x + v1y * v1y)
            val len2 = sqrt(v2x * v2x + v2y * v2y)
            val len3 = sqrt(v3x * v3x + v3y * v3y)

            if (len1 == 0f || len2 == 0f || len3 == 0f) return 0f

            // 计算余弦相似度
            val cos12 = (v1x * v2x + v1y * v2y) / (len1 * len2)
            val cos23 = (v2x * v3x + v2y * v3y) / (len2 * len3)

            // 伸直度 = 平均余弦相似度（转换为 0-1 范围）
            return ((cos12 + 1) / 2 + (cos23 + 1) / 2) / 2
        }

        /**
         * 检查其他手指是否收拢
         */
        private fun areOtherFingersFolded(landmarks: List<NormalizedLandmark>): Boolean {
            // 中指(9,10,11,12)、无名指(13,14,15,16)、小指(17,18,19,20)
            val fingerIndices = listOf(
                listOf(9, 10, 11, 12),   // 中指
                listOf(13, 14, 15, 16),  // 无名指
                listOf(17, 18, 19, 20)   // 小指
            )

            val wrist = landmarks[0]

            for (indices in fingerIndices) {
                val tip = landmarks[indices[3]]
                val pip = landmarks[indices[1]]

                // 如果指尖到手腕的距离 > PIP到手腕的距离，说明手指伸直了
                val tipToWrist = distance(tip, wrist)
                val pipToWrist = distance(pip, wrist)

                if (tipToWrist > pipToWrist * 1.1f) {
                    // 至少有一根手指伸直，不符合收拢要求
                    return false
                }
            }

            return true
        }

        /**
         * 计算手部尺寸（手腕到中指指尖距离）
         */
        private fun calculateHandSize(landmarks: List<NormalizedLandmark>): Float {
            val wrist = landmarks[0]
            val middleTip = landmarks[12]
            return distance(wrist, middleTip)
        }

        /**
         * 检查手部是否居中
         */
        private fun isHandCentered(landmarks: List<NormalizedLandmark>): Boolean {
            val centerX = landmarks.map { it.x() }.average().toFloat()
            val centerY = landmarks.map { it.y() }.average().toFloat()

            return centerX in HAND_EDGE_MARGIN..(1 - HAND_EDGE_MARGIN) &&
                   centerY in HAND_EDGE_MARGIN..(1 - HAND_EDGE_MARGIN)
        }

        /**
         * 计算综合置信度
         */
        private fun calculateConfidence(
            straightness: Float,
            othersFolded: Boolean,
            size: Float,
            centered: Boolean,
            angle: Float
        ): Float {
            var score = straightness * 0.4f  // 食指伸直度权重最高

            if (othersFolded) score += 0.25f  // 其他手指收拢
            if (size > HAND_MIN_SIZE) score += 0.15f  // 尺寸合适
            if (centered) score += 0.1f  // 位置居中

            // 角度是否在最佳区域中心
            val isOptimalAngle = angle in UP_ENTER_MIN..UP_ENTER_MAX ||
                                angle in DOWN_ENTER_MIN..DOWN_ENTER_MAX
            if (isOptimalAngle) score += 0.1f

            return score.coerceIn(0f, 1f)
        }

        /**
         * 使用迟滞判断方向
         */
        private fun determineDirectionWithHysteresis(
            angle: Float,
            quality: GestureQuality
        ): GestureDirection {
            // 质量不足时返回无方向
            if (!quality.isValid()) return GestureDirection.NONE

            return when (confirmedDirection) {
                GestureDirection.UP -> {
                    // 已在UP状态，使用退出阈值
                    if (angle in UP_EXIT_MIN..UP_EXIT_MAX) GestureDirection.UP
                    else if (angle in DOWN_ENTER_MIN..DOWN_ENTER_MAX) {
                        confirmedDirection = GestureDirection.DOWN
                        GestureDirection.DOWN
                    } else {
                        confirmedDirection = GestureDirection.NONE
                        GestureDirection.NONE
                    }
                }
                GestureDirection.DOWN -> {
                    // 已在DOWN状态，使用退出阈值
                    if (angle in DOWN_EXIT_MIN..DOWN_EXIT_MAX) GestureDirection.DOWN
                    else if (angle in UP_ENTER_MIN..UP_ENTER_MAX) {
                        confirmedDirection = GestureDirection.UP
                        GestureDirection.UP
                    } else {
                        confirmedDirection = GestureDirection.NONE
                        GestureDirection.NONE
                    }
                }
                else -> {
                    // 无状态，使用进入阈值
                    when {
                        angle in UP_ENTER_MIN..UP_ENTER_MAX -> {
                            confirmedDirection = GestureDirection.UP
                            GestureDirection.UP
                        }
                        angle in DOWN_ENTER_MIN..DOWN_ENTER_MAX -> {
                            confirmedDirection = GestureDirection.DOWN
                            GestureDirection.DOWN
                        }
                        else -> GestureDirection.NONE
                    }
                }
            }
        }

        /**
         * 计算稳定方向（基于历史缓冲区）
         */
        private fun calculateStableDirection(current: GestureDirection): GestureDirection {
            // 添加到缓冲区
            directionBuffer.addLast(current)
            if (directionBuffer.size > DIRECTION_BUFFER_SIZE) {
                directionBuffer.removeFirst()
            }

            // 如果缓冲区不够，返回NONE
            if (directionBuffer.size < DIRECTION_BUFFER_SIZE / 2) {
                return GestureDirection.NONE
            }

            // 统计各方向比例
            val total = directionBuffer.size.toFloat()
            val upCount = directionBuffer.count { it == GestureDirection.UP }.toFloat()
            val downCount = directionBuffer.count { it == GestureDirection.DOWN }.toFloat()

            val upRatio = upCount / total
            val downRatio = downCount / total

            return when {
                upRatio >= STABLE_DIRECTION_THRESHOLD -> GestureDirection.UP
                downRatio >= STABLE_DIRECTION_THRESHOLD -> GestureDirection.DOWN
                else -> GestureDirection.NONE
            }
        }

        private fun distance(p1: NormalizedLandmark, p2: NormalizedLandmark): Float {
            val dx = p1.x() - p2.x()
            val dy = p1.y() - p2.y()
            return sqrt(dx * dx + dy * dy)
        }

        fun getSmoothedAngle(): Float = smoothedAngle

        fun reset() {
            smoothedAngle = -1f
            directionBuffer.clear()
            confirmedDirection = GestureDirection.NONE
        }
    }

    /**
     * 稳健的手势状态机 - 使用积分式证据累积
     */
    inner class RobustGestureStateMachine {
        private var evidenceScore = 0
        private var targetDirection: GestureDirection = GestureDirection.NONE
        private var gestureStartTime: Long = 0
        private var lastTriggerTime: Long = 0
        private var lostFrameCount = 0

        fun process(direction: GestureDirection, quality: GestureQuality): GestureAction {
            val now = System.currentTimeMillis()

            // 冷却期检查
            if (currentGestureState == GestureState.COOLING) {
                if (now - lastTriggerTime > COOLDOWN_TIME) {
                    currentGestureState = GestureState.IDLE
                    LogUtils.d(TAG, "冷却结束，状态重置为 IDLE")
                } else {
                    return GestureAction.NONE
                }
            }

            return when (currentGestureState) {
                GestureState.IDLE -> handleIdleState(direction, quality, now)
                GestureState.CANDIDATE_UP -> handleCandidateUpState(direction, quality, now)
                GestureState.CANDIDATE_DOWN -> handleCandidateDownState(direction, quality, now)
                GestureState.CONFIRMED_UP -> handleConfirmedUpState(direction, now)
                GestureState.CONFIRMED_DOWN -> handleConfirmedDownState(direction, now)
                else -> GestureAction.NONE
            }
        }

        private fun handleIdleState(
            direction: GestureDirection,
            quality: GestureQuality,
            now: Long
        ): GestureAction {
            if (direction != GestureDirection.NONE && quality.isValid()) {
                // 开始累积证据
                targetDirection = direction
                evidenceScore = EVIDENCE_PER_FRAME
                currentGestureState = if (direction == GestureDirection.UP) {
                    GestureState.CANDIDATE_UP
                } else {
                    GestureState.CANDIDATE_DOWN
                }
                gestureStartTime = now
                Companion.currentGestureDirection = direction
                LogUtils.d(TAG, "进入候选状态: ${currentGestureState}")
                return GestureAction.START_TRACKING
            }
            return GestureAction.NONE
        }

        private fun handleCandidateUpState(
            direction: GestureDirection,
            quality: GestureQuality,
            now: Long
        ): GestureAction {
            return handleCandidateState(direction, quality, now, GestureDirection.UP, GestureState.CANDIDATE_UP)
        }

        private fun handleCandidateDownState(
            direction: GestureDirection,
            quality: GestureQuality,
            now: Long
        ): GestureAction {
            return handleCandidateState(direction, quality, now, GestureDirection.DOWN, GestureState.CANDIDATE_DOWN)
        }

        private fun handleCandidateState(
            direction: GestureDirection,
            quality: GestureQuality,
            now: Long,
            expectedDirection: GestureDirection,
            currentState: GestureState
        ): GestureAction {
            when {
                direction == expectedDirection && quality.isValid() -> {
                    // 方向匹配，增加证据
                    evidenceScore += (EVIDENCE_PER_FRAME * quality.confidence).toInt()
                    lostFrameCount = 0

                    // 检查是否达到确认阈值
                    if (evidenceScore >= CONFIRMATION_THRESHOLD) {
                        currentGestureState = if (expectedDirection == GestureDirection.UP) {
                            GestureState.CONFIRMED_UP
                        } else {
                            GestureState.CONFIRMED_DOWN
                        }
                        gestureStartTime = now
                        LogUtils.d(TAG, "手势确认: ${currentGestureState}")
                        return GestureAction.CONTINUE_TRACKING
                    }

                    return GestureAction.CONTINUE_TRACKING
                }
                direction == GestureDirection.NONE -> {
                    // 丢失帧，允许一定容错
                    lostFrameCount++
                    if (lostFrameCount > MAX_LOST_FRAMES) {
                        // 丢失太多帧，重置
                        resetToIdle()
                        return GestureAction.CANCEL_TRACKING
                    }
                    return GestureAction.CONTINUE_TRACKING
                }
                else -> {
                    // 方向改变，扣除证据
                    evidenceScore -= NOISE_PENALTY
                    if (evidenceScore <= 0) {
                        resetToIdle()
                        return GestureAction.CANCEL_TRACKING
                    }
                    return GestureAction.CONTINUE_TRACKING
                }
            }
        }

        private fun handleConfirmedUpState(direction: GestureDirection, now: Long): GestureAction {
            return handleConfirmedState(direction, now, GestureDirection.UP, GestureAction.TRIGGER_SWIPE_UP)
        }

        private fun handleConfirmedDownState(direction: GestureDirection, now: Long): GestureAction {
            return handleConfirmedState(direction, now, GestureDirection.DOWN, GestureAction.TRIGGER_SWIPE_DOWN)
        }

        private fun handleConfirmedState(
            direction: GestureDirection,
            now: Long,
            expectedDirection: GestureDirection,
            triggerAction: GestureAction
        ): GestureAction {
            val holdTime = now - gestureStartTime

            return when {
                direction == expectedDirection -> {
                    if (holdTime >= GESTURE_HOLD_TIME) {
                        // 触发滑动
                        currentGestureState = if (expectedDirection == GestureDirection.UP) {
                            GestureState.SWIPING_UP
                        } else {
                            GestureState.SWIPING_DOWN
                        }
                        LogUtils.i(TAG, "触发滑动，保持时间: ${holdTime}ms")
                        triggerAction
                    } else {
                        GestureAction.CONTINUE_TRACKING
                    }
                }
                direction == GestureDirection.NONE -> {
                    lostFrameCount++
                    if (lostFrameCount > MAX_LOST_FRAMES) {
                        resetToIdle()
                        return GestureAction.CANCEL_TRACKING
                    }
                    GestureAction.CONTINUE_TRACKING
                }
                else -> {
                    // 方向改变，取消
                    resetToIdle()
                    GestureAction.CANCEL_TRACKING
                }
            }
        }

        private fun resetToIdle() {
            currentGestureState = GestureState.IDLE
            Companion.currentGestureDirection = GestureDirection.NONE
            evidenceScore = 0
            lostFrameCount = 0
            LogUtils.d(TAG, "状态重置为 IDLE")
        }

        fun markSwipeCompleted() {
            currentGestureState = GestureState.COOLING
            lastTriggerTime = System.currentTimeMillis()
            Companion.currentGestureDirection = GestureDirection.NONE
            evidenceScore = 0
            lostFrameCount = 0
            LogUtils.i(TAG, "滑动完成，进入冷却期")
        }
    }

    /**
     * 手势动作枚举
     */
    enum class GestureAction {
        NONE,               // 无动作
        START_TRACKING,     // 开始跟踪
        CONTINUE_TRACKING,  // 继续跟踪
        CANCEL_TRACKING,    // 取消跟踪
        TRIGGER_SWIPE_UP,   // 触发上滑
        TRIGGER_SWIPE_DOWN  // 触发下滑
    }

    override fun onCreate() {
        super.onCreate()
        LogUtils.i(TAG, "手势识别服务创建")

        cameraExecutor = Executors.newSingleThreadExecutor()
        gestureAnalyzer = GestureAnalyzer()
        gestureStateMachine = RobustGestureStateMachine()

        handLandmarkerDetector = HandLandmarkerDetector(this) { result ->
            handleGestureResult(result)
        }
        handLandmarkerDetector.initialize()

        createNotificationChannel()
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
     * 处理手势识别结果 - 优化版本
     */
    private fun handleGestureResult(result: HandLandmarkerResult?) {
        updateHandLandmarkerResult(result)

        val gestureText = if (result != null && result.landmarks().isNotEmpty()) {
            val landmarks = result.landmarks().first()

            // 使用新的分析器
            val (direction, quality) = gestureAnalyzer.analyze(landmarks)
            Companion.currentGestureDirection = direction
            Companion.gestureQualityInfo = buildQualityInfo(quality, gestureAnalyzer.getSmoothedAngle())

            // 处理手势状态机
            val action = gestureStateMachine.process(direction, quality)
            handleGestureAction(action)

            // 生成状态文本
            buildGestureStatusText(direction, quality, result.landmarks().size)
        } else {
            // 没有检测到手，重置
            gestureAnalyzer.reset()
            gestureStateMachine.process(GestureDirection.NONE, GestureQuality(0f, false, 0f, false, false, 0f))
            Companion.currentGestureDirection = GestureDirection.NONE
            Companion.gestureQualityInfo = ""
            "未检测到手部"
        }

        updateGestureResult(gestureText)
        updateNotification(gestureText)

        LogUtils.d(TAG, "手势识别结果: $gestureText")
    }

    /**
     * 构建质量信息文本
     */
    private fun buildQualityInfo(quality: GestureQuality, angle: Float): String {
        return "角度:${"%.1f".format(angle)}° 伸直:${"%.2f".format(quality.indexStraightness)} " +
               "收拢:${if (quality.otherFingersFolded) "是" else "否"} " +
               "置信:${"%.2f".format(quality.confidence)}"
    }

    /**
     * 处理手势动作
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
            val eventTime = downTime

            // Down 事件
            val downEvent = obtainMethod.invoke(
                null,
                downTime, eventTime,
                0, // ACTION_DOWN
                centerX.toFloat(), (centerY + if (distance > 0) distance else 0).toFloat(),
                1.0f, 1.0f,  // pressure, size
                0, 0f, 0f,   // metaState, xPrecision, yPrecision
                0, 0         // deviceId, edgeFlags
            )

            injectMethod.invoke(inputManager, downEvent, 0)

            // Move 事件
            val moveEvent = obtainMethod.invoke(
                null,
                downTime, eventTime + 100,
                2, // ACTION_MOVE
                centerX.toFloat(), (centerY - if (distance > 0) 0 else -distance).toFloat(),
                1.0f, 1.0f,
                0, 0f, 0f,
                0, 0
            )

            injectMethod.invoke(inputManager, moveEvent, 0)

            // Up 事件
            val upEvent = obtainMethod.invoke(
                null,
                downTime, eventTime + 300,
                1, // ACTION_UP
                centerX.toFloat(), (centerY - if (distance > 0) 0 else -distance).toFloat(),
                1.0f, 1.0f,
                0, 0f, 0f,
                0, 0
            )

            injectMethod.invoke(inputManager, upEvent, 0)

            LogUtils.d(TAG, "触摸事件注入成功")

        } catch (e: Exception) {
            LogUtils.w(TAG, "触摸事件注入失败（需要系统权限）: ${e.message}")
        }
    }

    /**
     * 尝试使用 shell 命令执行滑动
     * 注意：需要 root 权限
     */
    private fun tryShellSwipe(centerX: Int, centerY: Int, distance: Int) {
        try {
            val startY = centerY + if (distance > 0) distance else 0
            val endY = centerY - if (distance > 0) 0 else -distance

            val command = "input swipe $centerX $startY $centerX $endY 300"
            val process = Runtime.getRuntime().exec(command)
            process.waitFor()

            if (process.exitValue() == 0) {
                LogUtils.d(TAG, "Shell 滑动命令执行成功")
            } else {
                LogUtils.w(TAG, "Shell 滑动命令执行失败，可能需要 root 权限")
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Shell 滑动命令异常: ${e.message}")
        }
    }

    /**
     * 构建手势状态文本
     */
    private fun buildGestureStatusText(
        direction: GestureDirection,
        quality: GestureQuality,
        handCount: Int
    ): String {
        val directionText = when (direction) {
            GestureDirection.UP -> "☝️ 向上"
            GestureDirection.DOWN -> "👇 向下"
            GestureDirection.NONE -> "🤚 无方向"
        }

        val stateText = when (currentGestureState) {
            GestureState.IDLE -> "等待中"
            GestureState.CANDIDATE_UP -> "向上候选"
            GestureState.CANDIDATE_DOWN -> "向下候选"
            GestureState.CONFIRMED_UP -> "向上确认"
            GestureState.CONFIRMED_DOWN -> "向下确认"
            GestureState.SWIPING_UP -> "触发上滑！"
            GestureState.SWIPING_DOWN -> "触发下滑！"
            GestureState.COOLING -> "冷却中..."
        }

        val qualityIndicator = if (quality.isValid()) "✓" else "✗"

        return "$directionText | $stateText | $qualityIndicator | 检测到 $handCount 只手"
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
            LogUtils.v(TAG, "检测到 ${result.handednesses().size} 只手, ${gestureQualityInfo}")
        }
    }

    private fun updatePreviewImage(image: Bitmap?) {
        lastPreviewImage = image
        if (image != null) {
            LogUtils.v(TAG, "更新预览图像: ${image.width}x${image.height}")
        }
    }
}