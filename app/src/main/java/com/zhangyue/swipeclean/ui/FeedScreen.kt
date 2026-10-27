package com.zhangyue.swipeclean.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zhangyue.swipeclean.MainViewModel
import com.zhangyue.swipeclean.data.MediaFilter
import com.zhangyue.swipeclean.data.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect

/**
 * 整理页（vc5 设计改版）：
 * - 视频轮/混合轮的视频顶层：真全屏沉浸（顶栏/底栏退场，悬浮胶囊替代）
 * - 配色统一：全黑底 + 白色主操作 + 待删红仅用于删除语义
 * - 圆角统一 20dp 体系；调试探针与快捷钮清除
 */
@Composable
fun FeedScreen(
    vm: MainViewModel,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    launchTrash: (android.content.IntentSender) -> Unit,
) {
    val ready by vm.ready.collectAsState()
    val feed by vm.feed.collectAsState()
    val filter by vm.feedFilter.collectAsState()
    val idx by vm.roundIndex.collectAsState()
    val undoTick by vm.undoTick.collectAsState()

    if (!ready) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("加载相册中...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        }
        return
    }

    val visible = remember(feed, undoTick, idx) { feed.drop(idx) }
    val stack = visible.take(3)
    val pool = rememberVideoPlayerPool()
    val muted by vm.videoUnmuted.collectAsState()
    val immersive = stack.firstOrNull()?.isVideo == true   // 视频顶层 → 全屏沉浸

    LaunchedEffect(muted) { pool.muted = muted }
    DisposableEffect(Unit) { onDispose { pool.releaseAll() } }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // —— 顶栏（仅非沉浸时显示）——
            if (!immersive) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChipRow(filter, vm::setFilter)
                    Spacer(Modifier.weight(1f))
                    PendingBadge(vm)
                    UndoButton(vm)
                }
                LinearProgressIndicator(
                    progress = { if (feed.isEmpty()) 0f else idx.toFloat() / feed.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(3.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
            }

            // —— 卡堆 ——
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (stack.isEmpty()) {
                    SettleCard(vm, launchTrash)
                } else {
                    CardStackView(vm, stack, scope, launchTrash, pool, muted)
                }
            }

            // —— 底部按钮排（仅非沉浸；沉浸轮的操作在卡内右侧竖排+悬浮角标）——
            if (!immersive && stack.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallFab(icon = { Icon(Icons.Filled.DeleteOutline, "删除", tint = Color(0xFFE11D48)) }) {
                        topCardSwipe(vm, stack.first(), scope, SwipeDir.LEFT)
                    }
                    BigTrashButton(
                        count = vm.session.pendingCount,
                        size = vm.repo.formatSize(vm.session.pendingBytes),
                    ) {
                        val sender = vm.buildTrashIntent()
                        if (sender != null) launchTrash(sender)
                    }
                    SmallFab(icon = { Icon(Icons.Filled.Favorite, "喜欢", tint = Color(0xFFF8FAFC)) }) {
                        topCardSwipe(vm, stack.first(), scope, SwipeDir.UP)
                    }
                }
            }
        }

        // —— 沉浸层悬浮控件（视频顶层时叠加）——
        if (immersive && stack.isNotEmpty()) {
            // 左上：筛选胶囊（点开切换面板）
            var filterOpen by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 14.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0x66000000),
                    onClick = { filterOpen = !filterOpen },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(filter.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(if (filterOpen) "▴" else "▾", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                    }
                }
                if (filterOpen) {
                    Surface(shape = CircleShape, color = Color(0x99000000)) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MediaFilter.entries.forEach { f ->
                                Text(
                                    f.label,
                                    color = if (f == filter) Color(0xFFE11D48) else Color.White.copy(alpha = 0.85f),
                                    fontSize = 13.sp,
                                    fontWeight = if (f == filter) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.clickable {
                                        filterOpen = false
                                        vm.setFilter(f)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            // 右上：待删角标 + 撤销（悬浮半透明）
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 14.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(shape = CircleShape, color = Color(0x66000000)) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text(
                            "${vm.session.pendingCount} 待删",
                            color = if (vm.session.pendingCount > 0) Color(0xFFF87171) else Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                        )
                    }
                }
                Surface(shape = CircleShape, color = Color(0x66000000), onClick = { vm.undo() }) {
                    Box(Modifier.padding(7.dp)) {
                        Icon(Icons.Filled.Restore, "撤销", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** 待删徽标（非沉浸顶栏用） */
@Composable
private fun PendingBadge(vm: MainViewModel) {
    val n = vm.session.pendingCount
    Text(
        "$n 待删",
        fontSize = 13.sp,
        color = if (n > 0) Color(0xFFE11D48) else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (n > 0) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
private fun UndoButton(vm: MainViewModel) {
    IconButton(onClick = { vm.undo() }) {
        Icon(Icons.Filled.Restore, contentDescription = "撤销")
    }
}

/** 本轮结算卡（黑底白字体系） */
@Composable
private fun SettleCard(vm: MainViewModel, launchTrash: (android.content.IntentSender) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (vm.session.pendingCount > 0) {
            Text("本轮整理完成", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "划掉 ${vm.session.pendingCount} 项 · 可释放 ${vm.repo.formatSize(vm.session.pendingBytes)}",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(22.dp))
            // 主按钮：白底黑字（黑底页面唯一的高权重按钮）
            Button(
                onClick = {
                    val sender = vm.buildTrashIntent()
                    if (sender != null) launchTrash(sender) else vm.nextRound()
                },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF0A0A0A),
                ),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("确认删除 ${vm.session.pendingCount} 项", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "不删了，全部还原并下一轮",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { vm.undoAll(); vm.nextRound() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        } else {
            Text("这里已经整理完了", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { vm.nextRound() },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0A0A0A)),
            ) { Text("下一轮") }
        }
    }
}

@Composable
private fun FilterChipRow(current: MediaFilter, onSelect: (MediaFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaFilter.entries.forEach { f ->
            FilterChip(
                selected = f == current,
                onClick = { onSelect(f) },
                label = { Text(f.label, fontSize = 12.sp) },
            )
        }
    }
}

private fun topCardSwipe(vm: MainViewModel, item: MediaItem, scope: CoroutineScope, dir: SwipeDir) {
    vm.onSwipe(dir, item)
}

@Composable
private fun CardStackView(
    vm: MainViewModel,
    stack: List<MediaItem>,
    scope: CoroutineScope,
    launchTrash: (android.content.IntentSender) -> Unit,
    pool: VideoPlayerPool,
    muted: Boolean,
) {
    val density = LocalDensity.current
    val topState = remember { CardDragState() }
    val dragScope = rememberCoroutineScope()
    val shareCtx = androidx.compose.ui.platform.LocalContext.current
    val immersive = stack.firstOrNull()?.isVideo == true

    LaunchedEffect(stack) {
        val next = stack.getOrNull(1)
        if (next?.isVideo == true) pool.preload(next)
    }
    LaunchedEffect(topState) {
        snapshotFlow { Pair(topState.pendingDX, topState.pendingDY) }
            .collect { _: Pair<Float, Float> -> topState.drainPending() }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth(if (immersive) 1f else 0.86f)
                .then(
                    if (immersive) Modifier.fillMaxHeight()
                    else Modifier.aspectRatio(0.75f)
                )
                .onSizeChanged { with(density) { topState.cardWidthPx = it.width.toFloat() } },
        ) {
            stack.asReversed().forEachIndexed { backIdx, item ->
                val i = stack.size - 1 - backIdx
                if (i == 0) {
                    if (item.isVideo) {
                        VideoCard(
                            item = item,
                            isTop = true,
                            dragState = topState,
                            scope = dragScope,
                            pool = pool,
                            muted = muted,
                            onToggleMute = { vm.toggleMute() },
                            onSwipe = { dir ->
                                pool.pauseCurrent()
                                topState.flyOut(dir, dragScope) { vm.onSwipe(dir, item) }
                            },
                            onLike = { vm.onLikeTap(item) },
                            onMarkDelete = {
                                pool.pauseCurrent()
                                topState.flyOut(SwipeDir.LEFT, dragScope) { vm.onSwipe(SwipeDir.LEFT, item) }
                            },
                            onShare = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "video/*"
                                    putExtra(android.content.Intent.EXTRA_STREAM, item.uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                shareCtx.startActivity(android.content.Intent.createChooser(send, "分享视频"))
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        MediaCard(
                            item = item,
                            modifier = Modifier
                                .fillMaxSize()
                                .cardDrag(topState, dragScope) { dir ->
                                    topState.flyOut(dir, dragScope) { vm.onSwipe(dir, item) }
                                },
                            overlayProgress = { topState.progress },
                        )
                    }
                } else {
                    val scale = 1f - i * 0.045f
                    val yOff = i * 14
                    MediaCard(
                        item = item,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationY = yOff.toFloat()
                                alpha = 1f - i * 0.25f
                            },
                        overlayProgress = { 0f },
                    )
                }
            }
        }
        if (!immersive) {
            Spacer(Modifier.height(8.dp))
            Text(
                "左滑删除 · 右滑跳过 · 上滑喜欢",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun MediaCard(
    item: MediaItem,
    modifier: Modifier,
    overlayProgress: () -> Float,
) {
    Box(modifier.clip(RoundedCornerShape(20.dp))) {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(formatDateCn(item.dateTaken), color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(formatSizeCn(item.size), color = Color.White, fontSize = 12.sp)
            }
        }
        val p = overlayProgress().coerceIn(0f, 1f)
        if (p > 0.02f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = p * 0.08f)),
            )
        }
    }
}

@Composable
private fun SmallFab(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color(0x14141414),
        contentColor = Color(0xFF0A0A0A),
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        onClick = onClick,
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun BigTrashButton(count: Int, size: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = count > 0,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (count > 0) Color.White else Color.White.copy(alpha = 0.25f),
            contentColor = Color(0xFF0A0A0A),
            disabledContainerColor = Color.White.copy(alpha = 0.25f),
            disabledContentColor = Color(0xFF0A0A0A).copy(alpha = 0.4f),
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (count > 0) "删除 $count 项 · $size" else "删除",
            fontWeight = FontWeight.SemiBold,
        )
    }
}

fun formatDateCn(sec: Long): String {
    if (sec <= 0) return "日期未知"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = sec * 1000 }
    return "${cal.get(java.util.Calendar.YEAR)}/${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
}

fun formatSizeCn(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}
