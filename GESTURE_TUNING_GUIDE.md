# 手势识别调优指南

本文档提供手势误触优化后的参数调优方法和验证流程。

## 一、优化改动总结

### 1. 方向特征计算改进
| 改动项 | 原方案 | 新方案 | 效果 |
|--------|--------|--------|------|
| 角度计算 | 仅用 PIP->TIP | MCP->TIP 和 PIP->TIP 加权组合 | 更稳定，减少局部抖动 |
| 手指姿态 | 无检查 | 食指伸直度 + 其他手指收拢检查 | 过滤误触（如弯曲手指） |
| 手部质量 | 无过滤 | 尺寸+位置检查 | 忽略过小/边缘手部 |

### 2. 时序平滑机制
| 机制 | 配置 | 作用 |
|------|------|------|
| EMA平滑 | alpha=0.3 | 平滑角度跳变 |
| 迟滞阈值 | 进入/退出两套区间 | 避免边界抖动 |
| 方向缓冲 | 7帧窗口，70%稳定度 | 确认方向需要持续一致 |

### 3. 积分式状态机
| 阶段 | 条件 | 说明 |
|------|------|------|
| IDLE | 初始状态 | 等待手势 |
| CANDIDATE | 证据分数>0 | 开始累积稳定度 |
| CONFIRMED | 证据分数>=100 | 手势已确认，开始计时 |
| SWIPING | 保持300ms | 触发滑动 |
| COOLING | 1000ms | 冷却期，防止重复触发 |

**证据分数机制**：
- 稳定帧：+20分 × 置信度
- 噪声帧：-30分
- 丢失帧：允许最多5帧丢失
- 确认阈值：100分

## 二、可调参数说明

### 2.1 GestureRecognitionService.kt 配置常量

```kotlin
// ========== 方向角度阈值（带迟滞）==========
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
```

**调整建议**：
- 如果向上指容易被误判为向下：缩小 DOWN_ENTER 区间（如 75-105）
- 如果想让手势更容易触发：放宽 ENTER 区间（如 240-300）

```kotlin
// ========== 手指姿态质量阈值 ==========
const val INDEX_STRAIGHTNESS_THRESHOLD = 0.85f  // 食指伸直度阈值
const val OTHER_FOLDED_THRESHOLD = 0.3f         // 其他手指收拢阈值
const val HAND_MIN_SIZE = 0.15f                 // 手部最小尺寸
const val HAND_EDGE_MARGIN = 0.1f               // 边缘距离阈值
```

**调整建议**：
- 如果要求更严格的手势：提高 INDEX_STRAIGHTNESS_THRESHOLD 到 0.9f
- 如果想放松对其他手指的要求：提高 OTHER_FOLDED_THRESHOLD

```kotlin
// ========== 时序平滑配置 ==========
const val EMA_ALPHA = 0.3f                      // EMA平滑系数（越小越平滑）
const val DIRECTION_BUFFER_SIZE = 7             // 方向缓冲窗口大小
const val STABLE_DIRECTION_THRESHOLD = 0.7f     // 稳定方向比例阈值
```

**调整建议**：
- 如果角度跳变频繁：降低 EMA_ALPHA（如 0.2f），增大 DIRECTION_BUFFER_SIZE（如 10）
- 如果想更快响应：提高 EMA_ALPHA（如 0.5f），减小 DIRECTION_BUFFER_SIZE（如 5）

```kotlin
// ========== 积分式状态机配置 ==========
const val CONFIRMATION_THRESHOLD = 100           // 确认手势所需积分
const val EVIDENCE_PER_FRAME = 20                // 每帧稳定方向提供的证据
const val NOISE_PENALTY = 30                     // 噪声帧扣除的证据
const val GESTURE_HOLD_TIME = 300L               // 手势保持时间（毫秒）
const val COOLDOWN_TIME = 1000L                  // 冷却时间（毫秒）
const val MAX_LOST_FRAMES = 5                    // 允许丢失的最大帧数
```

**调整建议**：
- 如果想更快触发：降低 CONFIRMATION_THRESHOLD（如 80），降低 GESTURE_HOLD_TIME（如 200）
- 如果想更严格防误触：提高 CONFIRMATION_THRESHOLD（如 150），提高 GESTURE_HOLD_TIME（如 500）
- 如果想允许更长的检测中断：提高 MAX_LOST_FRAMES（如 8）

### 2.2 HandLandmarkerDetector.kt 配置常量

```kotlin
const val MIN_DETECTION_CONFIDENCE = 0.5f      // 最小检测置信度
const val MIN_TRACKING_CONFIDENCE = 0.5f       // 最小跟踪置信度
const val MIN_PRESENCE_CONFIDENCE = 0.5f       // 最小存在置信度
const val MAX_NUM_HANDS = 1                      // 只检测一只手
```

**调整建议**：
- 如果 MediaPipe 检测不稳定：降低阈值（如 0.3f）
- 如果检测到太多假阳性：提高阈值（如 0.7f）

## 三、验证流程

### 3.1 ADB日志监控命令

```bash
# 监控手势识别服务日志（关键信息）
adb logcat -s GestureRecognitionService:I

# 监控详细调试信息
adb logcat -s GestureRecognitionService:D

# 监控检测器日志
adb logcat -s HandLandmarkerDetector:I

# 同时监控多个标签
adb logcat -s GestureRecognitionService:I,HandLandmarkerDetector:I
```

### 3.2 关键日志解读

**正常手势流程日志**：
```
I/GestureRecognitionService: 状态变更: IDLE -> POINTING_UP
D/GestureRecognitionService: 手势识别结果: ☝️ 向上 | 向上保持 | ✓ | 检测到 1 只手
I/GestureRecognitionService: 触发上滑手势，保持时间: 320ms
I/GestureRecognitionService: 滑动完成，进入冷却期
D/GestureRecognitionService: 冷却结束，状态重置为 IDLE
```

**质量不足被拒日志**：
```
V/GestureRecognitionService: 检测到 1 只手, 角度:265.5° 伸直:0.72 收拢:否 置信:0.58
D/GestureRecognitionService: 手势识别结果: ☝️ 向上 | 等待中 | ✗ | 检测到 1 只手
```

### 3.3 场景测试清单

#### 测试场景1：正常手势触发
- **操作**：伸直食指向上指，保持0.5秒
- **预期**：触发向下滑动（上一个视频）
- **通过标准**：日志显示 CONFIRMED_UP -> SWIPING_UP -> 滑动完成

#### 测试场景2：正常手势触发（向下）
- **操作**：伸直食指向下指，保持0.5秒
- **预期**：触发向上滑动（下一个视频）
- **通过标准**：日志显示 CONFIRMED_DOWN -> SWIPING_DOWN -> 滑动完成

#### 测试场景3：弯曲手指不误触
- **操作**：食指弯曲，其他手指伸直
- **预期**：不触发滑动
- **通过标准**：伸直度<0.85，手势被拒绝

#### 测试场景4：快速晃动不误触
- **操作**：手快速上下晃动（模拟吃饭时的晃动）
- **预期**：不触发滑动
- **通过标准**：方向不稳定，积分无法达到确认阈值

#### 测试场景5：张手不误触
- **操作**：五指张开，但未明确指向
- **预期**：不触发滑动
- **通过标准**：其他手指未收拢，手势被拒绝

#### 测试场景6：连续操作
- **操作**：快速连续做两次向下手势
- **预期**：第一次触发，第二次在冷却期结束后触发
- **通过标准**：两次滑动间隔约1-1.5秒

### 3.4 性能指标评估

**使用以下命令记录性能数据**：
```bash
adb logcat -s GestureRecognitionService:I > gesture_log.txt
```

**分析指标**：

| 指标 | 计算方法 | 目标值 |
|------|----------|--------|
| 误触发率 | 误触发次数 / 总非意图手势次数 | <5% |
| 平均响应延迟 | 手势确认到触发的时间 | 300-500ms |
| 手势识别成功率 | 成功触发 / 意图手势次数 | >90% |
| 连续操作成功率 | 连续两次成功触发 / 两次意图 | >80% |

## 四、常见问题排查

### 问题1：手势无法触发
**排查步骤**：
1. 检查日志中的质量信息：伸直度是否>0.85？其他手指是否收拢？
2. 检查角度是否在有效区间：日志中的角度值
3. 检查证据分数是否达到100

**解决方案**：
- 伸直食指，确保其他手指自然收拢
- 调整 INDEX_STRAIGHTNESS_THRESHOLD 到 0.8f
- 放宽角度阈值区间

### 问题2：响应太慢
**排查步骤**：
1. 检查 CONFIRMATION_THRESHOLD 和 GESTURE_HOLD_TIME 配置
2. 检查 DIRECTION_BUFFER_SIZE 和 EMA_ALPHA

**解决方案**：
- 降低 CONFIRMATION_THRESHOLD 到 80
- 降低 GESTURE_HOLD_TIME 到 200
- 降低 DIRECTION_BUFFER_SIZE 到 5

### 问题3：仍然误触发
**排查步骤**：
1. 检查是哪一步被误触发：候选、确认还是触发阶段
2. 检查误触发时的角度和质量信息

**解决方案**：
- 提高 INDEX_STRAIGHTNESS_THRESHOLD 到 0.9f
- 提高 CONFIRMATION_THRESHOLD 到 150
- 提高 GESTURE_HOLD_TIME 到 500
- 收紧角度阈值区间

### 问题4：MediaPipe检测不稳定
**排查步骤**：
1. 检查 HandLandmarkerDetector 日志中的置信度
2. 检查光照条件

**解决方案**：
- 降低 MIN_DETECTION_CONFIDENCE 到 0.3f
- 改善光照条件
- 检查摄像头是否被遮挡

## 五、调参建议流程

```
第一步：默认参数测试
  ↓ 记录误触发率和响应速度
  ↓
第二步：判断是否满足需求
  ├─ 误触多 → 收紧手指姿态阈值，提高确认阈值
  └─ 响应慢 → 降低保持时间，减小缓冲窗口
  ↓
第三步：细化调整
  ├─ 特定场景误触 → 调整对应角度阈值
  ├─ 检测不稳定 → 调整 MediaPipe 置信度
  └─ 连续操作困难 → 调整冷却时间
  ↓
第四步：回归测试
  ↓ 确保改动没有引入新问题
```

## 六、联系与反馈

如有问题，请记录以下信息：
1. 设备型号和 Android 版本
2. 具体的误触发场景描述
3. 对应的 ADB 日志片段
4. 当前使用的参数配置