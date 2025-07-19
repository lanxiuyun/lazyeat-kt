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
        // 控制区域的相对大小（占视图宽度的比例）
        private const val CONTROL_AREA_RATIO = 0.5f  // 控制区域占比约1/2
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

    // 控制区域的Paint
    private val controlAreaPaint = Paint()
    // 控制区域的范围
    private var controlAreaRect = RectF()

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

            // 初始化控制区域画笔
            controlAreaPaint.apply {
                color = Color.parseColor("#40673AB7")  // 调整透明度为25%
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            
            LogUtils.d(TAG, "画笔初始化完成")
        } catch (e: Exception) {
            LogUtils.e(TAG, "画笔初始化失败: ${e.message}")
            // 使用默认颜色作为备选
            linePaint.color = Color.CYAN
            pointPaint.color = Color.YELLOW
            controlAreaPaint.color = Color.parseColor("#40673AB7")
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        // 判断是否是横屏
        val isLandscape = w > h
        
        if (isLandscape) {
            // 横屏时，以高度为基准
            val controlHeight = h * CONTROL_AREA_RATIO
            val controlWidth = controlHeight * 3f / 4f  // 保持3:4比例
            
            // 计算居中位置
            val left = (w - controlWidth) / 2
            val top = (h - controlHeight) / 2
            
            controlAreaRect.set(left, top, left + controlWidth, top + controlHeight)
            LogUtils.d(TAG, "横屏 - 控制区域设置完成: left=$left, top=$top, width=$controlWidth, height=$controlHeight")
        } else {
            // 竖屏时，以宽度为基准
            val controlWidth = w * CONTROL_AREA_RATIO
            val controlHeight = controlWidth * 4f / 3f  // 保持4:3比例
            
            // 计算居中位置
            val left = (w - controlWidth) / 2
            val top = (h - controlHeight) / 2
            
            controlAreaRect.set(left, top, left + controlWidth, top + controlHeight)
            LogUtils.d(TAG, "竖屏 - 控制区域设置完成: left=$left, top=$top, width=$controlWidth, height=$controlHeight")
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

        // 绘制控制区域
        canvas.drawRect(controlAreaRect, controlAreaPaint)
        
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
                        // 直接使用归一化坐标映射到视图尺寸
                        val x = normalizedLandmark.x() * width
                        val y = normalizedLandmark.y() * height
                        
                        canvas.drawPoint(x, y, pointPaint)
                    }

                    // 绘制手部关键点之间的连接线
                    HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
                        val startPoint = landmark.get(connection!!.start())
                        val endPoint = landmark.get(connection.end())
                        
                        // 直接使用归一化坐标映射到视图尺寸
                        val startX = startPoint.x() * width
                        val startY = startPoint.y() * height
                        val endX = endPoint.x() * width
                        val endY = endPoint.y() * height
                        
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

        // 触发重绘
        invalidate()
    }

    /**
     * 检查点是否在控制区域内并返回相对位置
     * 如果点超出控制区域，则返回最近的边界值
     * @param x 点的x坐标
     * @param y 点的y坐标
     * @return Pair<Float, Float> 返回相对位置(0-1)
     */
    fun getRelativePosition(x: Float, y: Float): Pair<Float, Float> {
        // 计算相对于控制区域的位置，处理越界情况
        val clampedX = x.coerceIn(controlAreaRect.left, controlAreaRect.right)
        val clampedY = y.coerceIn(controlAreaRect.top, controlAreaRect.bottom)
        
        // 计算相对位置
        val relativeX = (clampedX - controlAreaRect.left) / controlAreaRect.width()
        val relativeY = (clampedY - controlAreaRect.top) / controlAreaRect.height()
        
        // 确保返回值在0-1范围内
        return Pair(
            relativeX.coerceIn(0f, 1f),
            relativeY.coerceIn(0f, 1f)
        ).also {
            if (x != clampedX || y != clampedY) {
                LogUtils.d(TAG, "点(x=$x, y=$y)超出控制区域，已限制为(x=$clampedX, y=$clampedY)")
            }
            LogUtils.d(TAG, "控制区域映射: 输入(x=$x, y=$y) -> 输出(x=${it.first}, y=${it.second})")
            LogUtils.d(TAG, "控制区域范围: left=${controlAreaRect.left}, top=${controlAreaRect.top}, right=${controlAreaRect.right}, bottom=${controlAreaRect.bottom}")
        }
    }
} 