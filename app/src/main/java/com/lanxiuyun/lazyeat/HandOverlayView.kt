package com.lanxiuyun.lazyeat

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.lanxiuyun.lazyeat.utils.LogUtils
import kotlin.math.max
import kotlin.math.min

/**
 * 手部关键点覆盖视图
 * 用于在相机预览上绘制手部关键点和连接线
 */
class HandOverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        private const val TAG = "HandOverlayView"
        // 关键点线条宽度
        private const val LANDMARK_STROKE_WIDTH = 8F
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

    // 缩放因子，用于适配不同尺寸的预览
    private var scaleFactor: Float = 1f
    
    // 图像尺寸
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

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
            
            // 初始化线条画笔 - 用于绘制手部关键点之间的连接线
            linePaint.color = ContextCompat.getColor(context!!, R.color.landmark_line_color)
            linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
            linePaint.style = Paint.Style.STROKE
            linePaint.isAntiAlias = true

            // 初始化关键点画笔 - 用于绘制手部关键点
            pointPaint.color = ContextCompat.getColor(context, R.color.landmark_point_color)
            pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
            pointPaint.style = Paint.Style.FILL
            pointPaint.isAntiAlias = true
            
            LogUtils.d(TAG, "画笔初始化完成")
        } catch (e: Exception) {
            LogUtils.e(TAG, "画笔初始化失败: ${e.message}")
            // 使用默认颜色作为备选
            linePaint.color = Color.CYAN
            pointPaint.color = Color.YELLOW
        }
    }

    /**
     * 绘制预览图像、手部关键点和连接线
     */
    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        
        // 首先绘制预览图像
        previewBitmap?.let { bitmap ->
            // 计算图像绘制区域，保持宽高比
            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
            val dstRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            
            // 绘制预览图像
            canvas.drawBitmap(bitmap, srcRect, dstRect, previewPaint)
        }
        
        // 绘制手部关键点和连接线
        results?.let { handLandmarkerResult ->
            try {
                // 遍历所有检测到的手
                for (handIndex in handLandmarkerResult.landmarks().indices) {
                    val landmark = handLandmarkerResult.landmarks()[handIndex]
                    LogUtils.d(TAG, "绘制第 ${handIndex + 1} 只手，关键点数量: ${landmark.size}")
                    
                    // 绘制每个关键点
                    for (pointIndex in landmark.indices) {
                        val normalizedLandmark = landmark[pointIndex]
                        val x = normalizedLandmark.x() * imageWidth * scaleFactor
                        val y = normalizedLandmark.y() * imageHeight * scaleFactor
                        
                        canvas.drawPoint(x, y, pointPaint)
                    }

                    // 绘制手部关键点之间的连接线
                    HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
                        val startPoint = landmark.get(connection!!.start())
                        val endPoint = landmark.get(connection.end())
                        
                        val startX = startPoint.x() * imageWidth * scaleFactor
                        val startY = startPoint.y() * imageHeight * scaleFactor
                        val endX = endPoint.x() * imageWidth * scaleFactor
                        val endY = endPoint.y() * imageHeight * scaleFactor
                        
                        canvas.drawLine(startX, startY, endX, endY, linePaint)
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "绘制手部关键点失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 设置预览图像
     * @param bitmap 预览图像
     */
    fun setPreviewImage(bitmap: Bitmap) {
        previewBitmap = bitmap
        invalidate()
    }

    /**
     * 设置手部识别结果并更新绘制
     * @param handLandmarkerResults 手部识别结果
     * @param imageHeight 输入图像高度
     * @param imageWidth 输入图像宽度
     * @param runningMode 运行模式（图像/视频/实时流）
     */
    fun setResults(
        handLandmarkerResults: HandLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.LIVE_STREAM
    ) {
        LogUtils.d(TAG, "设置手部识别结果，图像尺寸: ${imageWidth}x${imageHeight}，视图尺寸: ${width}x${height}")
        
        results = handLandmarkerResults

        // 根据屏幕方向调整图像尺寸
        val isPortrait = height > width
        this.imageWidth = if (isPortrait) imageHeight else imageWidth
        this.imageHeight = if (isPortrait) imageWidth else imageHeight

        // 根据运行模式计算缩放因子
        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                // 图像和视频模式：保持宽高比，居中显示
                min(width * 1f / this.imageWidth, height * 1f / this.imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                // 实时流模式：PreviewView使用FILL_START模式
                // 需要放大关键点以匹配捕获图像的显示尺寸
                max(width * 1f / this.imageWidth, height * 1f / this.imageHeight)
            }
        }
        
        LogUtils.d(TAG, "缩放因子: $scaleFactor，调整后尺寸: ${this.imageWidth}x${this.imageHeight}")
        
        // 触发重绘
        invalidate()
    }
} 