package com.zhangyue.swipeclean.ui

import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.media.AudioManager
import coil.compose.AsyncImage
import com.zhangyue.swipeclean.data.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 全屏视频卡（PRD 图1）：自动播放 + 循环 + 单击暂停 + 双击收藏 + 长按2x + 进度条seek
 * + 右侧竖排钮（声音/收藏/待删/分享）+ 底部信息栏。
 */
@Composable
fun VideoCard(
    item: MediaItem,
    isTop: Boolean,
    dragState: CardDragState,
    scope: CoroutineScope,
    pool: VideoPlayerPool,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onSwipe: (SwipeDir) -> Unit,
    onLike: () -> Unit,
    onMarkDelete: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val surfaceView = remember { SurfaceView(context) }
    var playing by remember { mutableStateOf(false) }
    var twoX by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(if (item.durationMs > 0) item.durationMs else 1L) }
    var showPlayIcon by remember { mutableStateOf(false) }
    var draggingProgress by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf(0L) }
    var decodeFailed by remember { mutableStateOf(false) }
    val heartScale = remember { Animatable(1f) }

    // ---- 顶层：起播；非顶层：停 ----
    LaunchedEffect(isTop, item.id) {
        if (isTop) {
            val player = pool.play(item, surfaceView)
            playing = true
            decodeFailed = player == null
            // 预载后层第 1 张由父层触发（pool.preload）
        } else {
            pool.pauseCurrent()
            playing = false
        }
    }

    // ---- 进度驱动：Player 回调帧（不轮询 API，用 100ms 帧同步本地状态即可，Pool 内部读 currentPosition）----
    LaunchedEffect(isTop, playing) {
        while (isTop && playing && !draggingProgress) {
            positionMs = pool.currentPosition
            val d = pool.duration
            if (d > 0) durationMs = d
            delay(100)
        }
    }

    // ---- 手势：仲裁（四向拖拽×单击×双击×长按）----
    // 注意：手势层要挂在 Box 最外（modifier 链首），且不能被 AndroidView/Surface 挡住触摸。
    val gestureModifier = if (isTop) {
        Modifier
            .videoCardGestures(
            state = dragState,
            scope = scope,
            onSwipe = onSwipe,
            onTap = {
                if (playing) {
                    pool.pauseCurrent(); playing = false
                } else {
                    pool.resumeCurrent(); playing = true
                }
                showPlayIcon = true
                scope.launch { delay(600); showPlayIcon = false }
            },
            onDoubleTap = {
                onLike()
                scope.launch {
                    heartScale.snapTo(1.6f)
                    heartScale.animateTo(1f, tween(350))
                }
            },
            onLongPressStart = {
                twoX = true
                pool.setSpeed(2f)
            },
            onLongPressEnd = {
                twoX = false
                pool.setSpeed(1f)
            },
        )
    } else Modifier

    Box(
        modifier
            .then(gestureModifier)
            .offset {
                androidx.compose.ui.unit.IntOffset(
                    dragState.x.value.roundToInt2(), dragState.y.value.roundToInt2()
                )
            }
            .rotate(dragState.rotation)
            .background(Color.Black)
    ) {
        // 视频渲染层（Surface 不参与触摸：setZOrderMediaOverlay 下层渲染，事件穿透给 Compose 层）
        if (isTop && !decodeFailed) {
            AndroidView(
                factory = {
                    surfaceView.setZOrderMediaOverlay(false)
                    // SurfaceView 本身不拦截触摸（默认 focusable=false），但保险起见显式关闭
                    surfaceView.isFocusable = false
                    surfaceView.isEnabled = false
                    surfaceView
                },
                modifier = Modifier.fillMaxSize(),
                update = { view -> pool.playerAttach(view) },
            )
        } else {
            // 封面帧：非顶层 / 起播前 / 解码失败兜底（黑场消除）
            AsyncImage(
                model = item.uri.toString(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 左红右绿上黄描边（拖拽进度）
        val p = dragState.progress.coerceIn(0f, 1f)
        if (p > 0.02f) {
            val edge = when {
                dragState.y.value < -40f -> Color(0xFFF59E0B)
                dragState.x.value < -40f -> Color(0xFFE11D48)
                dragState.x.value > 40f -> Color(0xFF22C55E)
                else -> Color.Transparent
            }
            if (edge != Color.Transparent) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(5.dp, edge, RoundedCornerShape(4.dp))
                        .alpha(p)
                )
            }
        }

        // 2x 角标（长按中）
        if (twoX) {
            Text(
                "2x »",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC22C55E))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }

        // 中央 ▶ / ❤️
        Box(Modifier.align(Alignment.Center)) {
            if (showPlayIcon && !playing) {
                Box(
                    Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▶", color = Color.White, fontSize = 26.sp)
                }
            }
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "收藏",
                tint = Color(0xFFF43F5E),
                modifier = Modifier
                    .size(90.dp)
                    .scale(heartScale.value)
                    .alpha(if (heartScale.value > 1.02f) 1f else 0f),
            )
        }

        // 右侧竖排操作列（对标卡待删 shot6）
        if (isTop) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ActionBubble(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, "声音") { onToggleMute() }
                ActionBubble(Icons.Filled.FavoriteBorder, "收藏") {
                    onLike()
                    scope.launch { heartScale.snapTo(1.6f); heartScale.animateTo(1f, tween(350)) }
                }
                ActionBubble(Icons.Filled.Delete, "待删", tint = Color(0xFFE11D48)) { onMarkDelete() }
                ActionBubble(Icons.Filled.Share, "分享") { onShare() }
            }
        }

        // 底部信息栏 + 进度条
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    videoInfoLine(item),
                    color = Color.White,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    resolutionBadge(item),
                    color = if (item.size >= 200L * 1024 * 1024) Color(0xFFFBBF24) else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(6.dp))
            VideoProgressBar(
                positionMs = if (draggingProgress) scrubMs else positionMs,
                durationMs = durationMs,
                dragging = draggingProgress,
                onScrubStart = {
                    draggingProgress = true
                    pool.pauseCurrent(); playing = false
                },
                onScrub = { scrubMs = it },
                onScrubEnd = {
                    pool.seekTo(scrubMs)
                    pool.resumeCurrent(); playing = true
                    draggingProgress = false
                },
            )
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(if (draggingProgress) scrubMs else positionMs), color = Color.White, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(formatTime(durationMs), color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

    /** 池内当前播放器挂 surface（AndroidView.update 调用，避免 factory 时序问题） */
    private fun VideoPlayerPool.playerAttach(view: SurfaceView) {
        // play() 已挂 surface；这里兜底重挂（重组时 surface 复用）
        attachSurface(view)
    }

/** 进度条：细线常显，按住变粗 + 拖动 seek（PRD 图1 标注7） */
@Composable
private fun VideoProgressBar(
    positionMs: Long,
    durationMs: Long,
    dragging: Boolean,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(1f) }
    val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(if (dragging) 14.dp else 20.dp)
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        widthPx = size.width.toFloat()
                        onScrubStart()
                        onScrub(((offset.x / size.width) * durationMs).toLong().coerceIn(0, durationMs))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        onScrub(((change.position.x / size.width) * durationMs).toLong().coerceIn(0, durationMs))
                    },
                    onDragEnd = { onScrubEnd() },
                    onDragCancel = { onScrubEnd() },
                )
            }
            .clickable(enabled = false) {}
    ) {
        widthPx = size.width
        val trackH = if (dragging) 7f else 4f
        val y = size.height / 2
        drawRoundRect(
            Color.White.copy(alpha = 0.3f),
            Offset(0f, y - trackH / 2),
            Size(size.width, trackH),
            CornerRadius(trackH),
        )
        drawRoundRect(
            Color.White,
            Offset(0f, y - trackH / 2),
            Size(size.width * frac, trackH),
            CornerRadius(trackH),
        )
        if (dragging) {
            drawCircle(Color.White, radius = trackH * 1.6f, center = Offset(size.width * frac, y))
        }
    }
}

@Composable
private fun ActionBubble(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = cd, tint = tint, modifier = Modifier.size(22.dp))
    }
}

private fun videoInfoLine(item: MediaItem): String {
    val date = formatDateCn(item.dateTaken)
    val dur = formatTime(if (item.durationMs > 0) item.durationMs else 0L)
    return "$date · $dur · ${formatSizeCn(item.size)}"
}

private fun resolutionBadge(item: MediaItem): String {
    val h = item.height
    return when {
        h >= 2160 -> "4K·大"
        h >= 1080 -> "1080P"
        h > 0 -> "标清"
        else -> ""
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun Float.roundToInt2(): Int = this.toInt()
