package com.zhangyue.swipeclean.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 滑卡方向 */
enum class SwipeDir { LEFT, RIGHT, UP, NONE }

/**
 * 顶层卡拖拽状态：跟手位移+旋转，过阈值触发回调并飞出。
 * 阈值 = 卡宽 0.35 / 上滑 0.30。
 */
class CardDragState {
    val x = Animatable(0f)
    val y = Animatable(0f)
    var cardWidthPx: Float by mutableStateOf(800f)
    var enabled: Boolean by mutableStateOf(true)

    /** 手势层攒下的位移，由 Compose 重组帧消费（snapTo 是 suspend 不能在 awaitEachGesture 里直调） */
    var pendingDX: Float by mutableStateOf(0f)
    var pendingDY: Float by mutableStateOf(0f)

    /** 长按 2x 中：拖拽挂起（大幅位移才解挂，PRD 图2） */
    var longPressing: Boolean by mutableStateOf(false)

    val rotation: Float
        get() = (x.value / cardWidthPx * 14f).coerceIn(-15f, 15f)

    val progress: Float
        get() = maxOf(abs(x.value) / cardWidthPx, abs(y.value) / (cardWidthPx * 0.9f))
            .coerceIn(0f, 1f)

    fun flyOut(dir: SwipeDir, scope: CoroutineScope, onDone: () -> Unit) {
        enabled = false
        val targetX = when (dir) {
            SwipeDir.LEFT -> -cardWidthPx * 1.6f
            SwipeDir.RIGHT -> cardWidthPx * 1.6f
            else -> x.value
        }
        val targetY = if (dir == SwipeDir.UP) -cardWidthPx * 1.8f else y.value
        scope.launch {
            x.animateTo(targetX, tween(200))
            y.animateTo(targetY, tween(200))
            onDone()
            x.snapTo(0f); y.snapTo(0f)
            enabled = true
        }
    }

    fun springBack(scope: CoroutineScope) {
        scope.launch {
            x.animateTo(0f, tween(180))
            y.animateTo(0f, tween(180))
        }
    }

    /** 供 Compose 侧 LaunchedEffect 消费手势位移（协程上下文里才能 suspend） */
    suspend fun drainPending() {
        if (pendingDX != 0f || pendingDY != 0f) {
            val dx = pendingDX; val dy = pendingDY
            pendingDX = 0f; pendingDY = 0f
            x.snapTo(x.value + dx)
            y.snapTo(y.value + dy)
        }
    }
}

/** 手势仲裁常量（PRD 图2）：集中可调 */
object GestureThresholds {
    val LONG_PRESS_MS = 300L
    val DOUBLE_TAP_MS = 250L
    val SLOP_CANCEL_FACTOR = 3f   // 长按中位移超过 slop×3 → 取消 2x 转拖拽
}

/**
 * 视频卡手势仲裁：四向拖拽 / 单击 / 双击 / 长按2x 共存（PRD 图2 判定流）。
 * 拖拽沿用以 slop（viewConfiguration.touchSlop）为界；双击窗口 250ms；长按 300ms 且位移 < slop。
 *
 * 与 detectDragGestures 的区别：不能直接用两个 pointerInput 叠加（都消费会打架），
 * 这里一个 awaitEachGesture 内完整实现仲裁。
 */
fun Modifier.videoCardGestures(
    state: CardDragState,
    scope: CoroutineScope,
    onSwipe: (SwipeDir) -> Unit,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
): Modifier = this.pointerInput(state.enabled) {
    if (!state.enabled) return@pointerInput
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        com.zhangyue.swipeclean.ui.GestureDebug.downs++
        var dragging = false
        var longPressing = false
        val downPos = down.position
        val downTime = System.currentTimeMillis()

        // 单击/双击结算延迟 job
        var tapJob: kotlinx.coroutines.Job? = null

        fun settleUp() {
        val dt = System.currentTimeMillis() - downTime
        if (longPressing) {
            onLongPressEnd(); longPressing = false; state.longPressing = false
        }
        if (!dragging && dt < GestureThresholds.LONG_PRESS_MS) {
            val now = System.currentTimeMillis()
            if (now - lastTapAt < GestureThresholds.DOUBLE_TAP_MS) {
                // 第二击 → 双击收藏
                lastTapAt = 0L
                tapJob?.cancel(); tapJob = null
                onDoubleTap()
            } else {
                // 第一击：挂起等双击窗，超时算单击暂停
                lastTapAt = now
                val firstTapAt = now
                tapJob?.cancel()
                tapJob = scope.launch {
                    delay(GestureThresholds.DOUBLE_TAP_MS)
                    if (lastTapAt == firstTapAt) {
                        lastTapAt = 0L
                        onTap()
                    }
                }
            }
        }
        }

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            val total = change.position - downPos
            val dist = if (total.x.isNaN() || total.y.isNaN()) 0f else kotlin.math.hypot(total.x, total.y)

            if (change.changedToUp()) {
                change.consume()
                settleUp()
                break
            }

            if (!dragging && !longPressing && dist > slop) {
                dragging = true
                com.zhangyue.swipeclean.ui.GestureDebug.drags++
                tapJob?.cancel(); tapJob = null   // 拖拽开始 → 撤销待结算单击
                lastTapAt = 0L
            }
            if (dist > com.zhangyue.swipeclean.ui.GestureDebug.maxDist) {
                com.zhangyue.swipeclean.ui.GestureDebug.maxDist = dist
            }

            if (System.currentTimeMillis() - downTime >= GestureThresholds.LONG_PRESS_MS &&
                !dragging && !longPressing && dist <= slop
            ) {
                // 长按生效 → 2x
                longPressing = true
                state.longPressing = true
                onLongPressStart()
            }

            if (longPressing && dist > slop * GestureThresholds.SLOP_CANCEL_FACTOR) {
                // 长按中大幅位移 → 退出 2x 转拖拽
                onLongPressEnd(); longPressing = false; state.longPressing = false
                dragging = true
            }

            if (dragging) {
                change.consume()
                val dx = change.positionChange().x
                val dy = change.positionChange().y
                // 跟手位移：协程里 snapTo（照片卡同款模式；awaitEachGesture 内不能直调 suspend）
                scope.launch {
                    state.x.snapTo(state.x.value + dx)
                    state.y.snapTo(state.y.value + dy)
                }
            }
        }
        // 拖拽结束判定（若正在拖拽中抬起，上面 break 前未走阈值判定）
        com.zhangyue.swipeclean.ui.GestureDebug.ups++
        if (dragging) {
            val thx = state.cardWidthPx * 0.35f
            val thy = state.cardWidthPx * 0.30f
            val dx = state.x.value
            val dy = state.y.value
            when {
                dy < -thy && abs(dx) < thx -> onSwipe(SwipeDir.UP)
                dx > thx -> onSwipe(SwipeDir.RIGHT)
                dx < -thx -> onSwipe(SwipeDir.LEFT)
                else -> state.springBack(scope)
            }
        }
        state.pendingDX = 0f; state.pendingDY = 0f   // 结算后清残留，防飞出后抖动
    }
}

/** 最近一次抬起时间（双击窗口判定，模块级共享——单卡堆单手势层，无并发问题） */
internal var lastTapAt = 0L

private fun androidx.compose.ui.input.pointer.PointerInputChange.changedToUp(): Boolean = !pressed && previousPressed

private fun Offset.getDistance(): Float = kotlin.math.hypot(x, y)

/** 给顶层卡挂手势 + 位移/旋转（照片卡沿用） */
fun Modifier.cardDrag(state: CardDragState, scope: CoroutineScope, onSwipe: (SwipeDir) -> Unit): Modifier =
    this
        .offset { IntOffset(state.x.value.roundToInt(), state.y.value.roundToInt()) }
        .rotate(state.rotation)
        .pointerInput(state.enabled) {
            if (!state.enabled) return@pointerInput
            detectDragGestures(
                onDragEnd = {
                    val thx = state.cardWidthPx * 0.35f
                    val thy = state.cardWidthPx * 0.30f
                    when {
                        state.y.value < -thy && abs(state.x.value) < thx -> onSwipe(SwipeDir.UP)
                        state.x.value > thx -> onSwipe(SwipeDir.RIGHT)
                        state.x.value < -thx -> onSwipe(SwipeDir.LEFT)
                        else -> state.springBack(scope)
                    }
                },
                onDragCancel = { state.springBack(scope) },
                onDrag = { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        state.x.snapTo(state.x.value + dragAmount.x)
                        state.y.snapTo(state.y.value + dragAmount.y)
                    }
                },
            )
        }

