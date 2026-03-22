package com.lanxiuyun.lazyeat

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.lanxiuyun.lazyeat.service.GestureRecognitionService
import com.lanxiuyun.lazyeat.utils.LogUtils
import kotlin.math.cos
import kotlin.math.sin

/**
 * 手部关键点覆盖视图 - 吃饭刷抖音助手
 * 用于在相机预览上绘制手部关键点、连接线和手势方向指示
 */
class HandOverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        private const val TAG = "HandOverlayView"
        private const val LANDMARK_STROKE_WIDTH = 8F
        private const val DIRECTION_ARROW_SIZE = 80f
        private const val DIRECTION_ARROW_WIDTH = 12f
    }

    // 手部识别结果
    private var results: HandLandmarkerResult? = null

    // 预览图像
    private var previewBitmap: Bitmap? = null
    private val previewPaint = Paint()

    // 绘制线条的画笔
    private var linePaint = Paint()

    // 绘制关键点的画笔
    private var pointPaint = Paint()

    // 方向箭头画笔
    private val arrowPaint = Paint()
    private val arrowFillPaint = Paint()

    // 状态文字画笔
    private val statusPaint = Paint()

    // 背景画笔
    private val statusBackgroundPaint = Paint()

    // 当前手势方向和状态
    private var currentDirection: GestureRecognitionService.GestureDirection = GestureRecognitionService.GestureDirection.NONE
    private var currentState: GestureRecognitionService.GestureState = GestureRecognitionService.GestureState.IDLE

    init {
        initPaints()
        LogUtils.i(TAG, "HandOverlayView 初始化完成")
    }

    /**
     * 清除绘制内容
     */
    fun clear() {
        LogUtils.d(TAG, "清除手部覆盖视图")
        results = null
        previewBitmap = null
        currentDirection = GestureRecognitionService.GestureDirection.NONE
        currentState = GestureRecognitionService.GestureState.IDLE
        linePaint.reset()
        pointPaint.reset()
        invalidate()
        initPaints()
    }

    /**
     * 初始化画笔样式
     */
    private fun initPaints() {
        try {
            // 初始化预览图像画笔
            previewPaint.isFilterBitmap = true

            // 初始化线条画笔
            linePaint.color = ContextCompat.getColor(context!!, R.color.landmark_line_color)
            linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
            linePaint.style = Paint.Style.STROKE
            linePaint.isAntiAlias = true

            // 初始化关键点画笔
            pointPaint.color = ContextCompat.getColor(context, R.color.landmark_point_color)
            pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
            pointPaint.style = Paint.Style.FILL
            pointPaint.isAntiAlias = true

            // 初始化方向箭头画笔
            arrowPaint.apply {
                color = Color.GREEN
                strokeWidth = DIRECTION_ARROW_WIDTH
                style = Paint.Style.STROKE
                isAntiAlias = true
            }

            arrowFillPaint.apply {
                color = Color.argb(128, 0, 255, 0)
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            // 初始化状态文字画笔
            statusPaint.apply {
                color = Color.WHITE
                textSize = 48f
                isFakeBoldText = true
                isAntiAlias = true
            }

            // 初始化状态背景画笔
            statusBackgroundPaint.apply {
                color = Color.argb(160, 0, 0, 0)
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            LogUtils.d(TAG, "画笔初始化完成")
        } catch (e: Exception) {
            LogUtils.e(TAG, "画笔初始化失败: ${e.message}")
            linePaint.color = Color.CYAN
            pointPaint.color = Color.YELLOW
            arrowPaint.color = Color.GREEN
            statusPaint.color = Color.WHITE
            statusBackgroundPaint.color = Color.argb(160, 0, 0, 0)
        }
    }

    /**
     * 设置手势方向和状态
     */
    fun setGestureInfo(direction: GestureRecognitionService.GestureDirection, state: GestureRecognitionService.GestureState) {
        currentDirection = direction
        currentState = state

        // 根据方向和状态更新箭头颜色
        when (direction) {
            GestureRecognitionService.GestureDirection.UP -> {
                arrowPaint.color = Color.GREEN
                arrowFillPaint.color = Color.argb(128, 0, 255, 0)
            }
            GestureRecognitionService.GestureDirection.DOWN -> {
                arrowPaint.color = Color.RED
                arrowFillPaint.color = Color.argb(128, 255, 0, 0)
            }
            GestureRecognitionService.GestureDirection.NONE -> {
                arrowPaint.color = Color.GRAY
                arrowFillPaint.color = Color.argb(128, 128, 128, 128)
            }
        }

        invalidate()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // 绘制预览图像
        previewBitmap?.let { bitmap ->
            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
            val dstRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bitmap, srcRect, dstRect, previewPaint)
        }

        // 绘制手部关键点和连接线
        results?.let { handLandmarkerResult ->
            try {
                for (handIndex in handLandmarkerResult.landmarks().indices) {
                    val landmark = handLandmarkerResult.landmarks()[handIndex]

                    // 绘制关键点
                    for (pointIndex in landmark.indices) {
                        val normalizedLandmark = landmark[pointIndex]
                        val x = normalizedLandmark.x() * width
                        val y = normalizedLandmark.y() * height

                        canvas.drawCircle(x, y, 8f, pointPaint)
                    }

                    // 绘制连接线
                    HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
                        val startPoint = landmark.get(connection!!.start())
                        val endPoint = landmark.get(connection.end())

                        val startX = startPoint.x() * width
                        val startY = startPoint.y() * height
                        val endX = endPoint.x() * width
                        val endY = endPoint.y() * height

                        canvas.drawLine(startX, startY, endX, endY, linePaint)
                    }

                    // 绘制手势方向指示（使用食指关键点）
                    if (landmark.size >= 9) {
                        drawDirectionArrow(canvas, landmark)
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "绘制手部关键点失败: ${e.message}")
            }
        }

        // 绘制状态信息
        drawStatusInfo(canvas)
    }

    /**
     * 绘制方向箭头
     */
    private fun drawDirectionArrow(
        canvas: Canvas,
        landmark: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ) {
        val pip = landmark[6]   // 近端指间关节
        val tip = landmark[8]   // 指尖

        val startX = pip.x() * width
        val startY = pip.y() * height
        val endX = tip.x() * width
        val endY = tip.y() * height

        // 计算方向向量
        val dx = endX - startX
        val dy = endY - startY
        val length = kotlin.math.hypot(dx, dy)

        if (length > 0) {
            // 归一化方向向量
            val dirX = dx / length
            val dirY = dy / length

            // 绘制方向箭头
            val arrowLength = 120f
            val arrowEndX = startX + dirX * arrowLength
            val arrowEndY = startY + dirY * arrowLength

            // 绘制主线
            canvas.drawLine(startX, startY, arrowEndX, arrowEndY, arrowPaint)

            // 绘制箭头头部
            val headLength = 40f
            val headAngle = Math.PI / 6  // 30度

            val angle = kotlin.math.atan2(dirY.toDouble(), dirX.toDouble())

            val x1 = arrowEndX - headLength * cos(angle - headAngle).toFloat()
            val y1 = arrowEndY - headLength * sin(angle - headAngle).toFloat()
            val x2 = arrowEndX - headLength * cos(angle + headAngle).toFloat()
            val y2 = arrowEndY - headLength * sin(angle + headAngle).toFloat()

            val path = Path().apply {
                moveTo(arrowEndX, arrowEndY)
                lineTo(x1, y1)
                lineTo(x2, y2)
                close()
            }

            canvas.drawPath(path, arrowFillPaint)
            canvas.drawPath(path, arrowPaint)
        }
    }

    /**
     * 绘制状态信息
     */
    private fun drawStatusInfo(canvas: Canvas) {
        val directionText = when (currentDirection) {
            GestureRecognitionService.GestureDirection.UP -> "☝️ 向上"
            GestureRecognitionService.GestureDirection.DOWN -> "👇 向下"
            GestureRecognitionService.GestureDirection.NONE -> "🤚 无方向"
        }

        val stateText = when (currentState) {
            GestureRecognitionService.GestureState.IDLE -> "等待中"
            GestureRecognitionService.GestureState.POINTING_UP -> "向上保持..."
            GestureRecognitionService.GestureState.POINTING_DOWN -> "向下保持..."
            GestureRecognitionService.GestureState.SWIPING_UP -> "触发上滑！"
            GestureRecognitionService.GestureState.SWIPING_DOWN -> "触发下滑！"
            GestureRecognitionService.GestureState.COOLING -> "冷却中..."
        }

        val text = "$directionText | $stateText"

        // 计算文字位置
        val x = 20f
        val y = 80f

        // 绘制背景
        val bounds = Rect()
        statusPaint.getTextBounds(text, 0, text.length, bounds)
        val padding = 16f
        canvas.drawRect(
            x - padding,
            y - bounds.height() - padding,
            x + bounds.width() + padding,
            y + padding,
            statusBackgroundPaint
        )

        // 绘制文字
        canvas.drawText(text, x, y, statusPaint)
    }

    /**
     * 设置预览图像
     */
    fun setPreviewImage(bitmap: Bitmap) {
        previewBitmap = bitmap
        invalidate()
    }

    /**
     * 设置手部识别结果并更新绘制
     */
    fun setResults(
        handLandmarkerResults: HandLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.LIVE_STREAM
    ) {
        LogUtils.d(TAG, "设置手部识别结果，图像尺寸: ${imageWidth}x${imageHeight}，视图尺寸: ${width}x${height}")

        results = handLandmarkerResults
        invalidate()
    }
}
