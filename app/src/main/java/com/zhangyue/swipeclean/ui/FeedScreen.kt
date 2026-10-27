package com.zhangyue.swipeclean.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect

/** 整理页：滑卡堆叠 + 顶栏筛选/进度 + 底栏操作（视频轮全屏沉浸） */
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
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载相册中...")
        }
        return
    }

    // 关键：key(undoTick) 让卡堆在撤销时整体重建
    val visible = remember(feed, undoTick, idx) { feed.drop(idx) }
    val stack = visible.take(3)
    val pool = rememberVideoPlayerPool()
    val muted by vm.videoUnmuted.collectAsState()

    // 视频声音偏好同步到播放器池
    LaunchedEffect(muted) { pool.muted = muted }
    // 切走整理页/换轮时全量释放播放器（释放纪律）
    DisposableEffect(Unit) { onDispose { pool.releaseAll() } }

    Column(modifier.fillMaxSize()) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChipRow(filter, vm::setFilter)
            Spacer(Modifier.weight(1f))
            Text(
                "${vm.session.pendingCount} 待删",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = { vm.undo() }) {
                Icon(Icons.Filled.Restore, contentDescription = "撤销")
            }
        }
        // 进度条
        LinearProgressIndicator(
            progress = { if (feed.isEmpty()) 0f else idx.toFloat() / feed.size },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(3.dp),
        )

        // 卡堆
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (stack.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("这里已经整理完了", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.onTrashConfirmed(); vm.nextRound() }) {
                        Text("下一轮")
                    }
                }
            } else {
                CardStackView(vm, stack, scope, launchTrash, pool, muted)
            }
        }

        // 底部按钮区
        if (stack.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (filter == MediaFilter.VIDEOS) {
                    Text(
                        com.zhangyue.swipeclean.ui.GestureDebug.snapshot(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                if (filter == MediaFilter.PHOTOS) {
                    Text(
                        "→视频",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            com.zhangyue.swipeclean.ui.GestureDebug.reset()
                            vm.setFilter(MediaFilter.VIDEOS)
                        },
                    )
                }
                SmallFab(icon = { Icon(Icons.Filled.Favorite, "喜欢", tint = Color(0xFFE11D48)) }) {
                    topCardSwipe(vm, stack.first(), scope, SwipeDir.UP)
                }
            }
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
    // 底部按钮复用拖拽路径
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

    // 预载后层第 1 张（双实例池 B，PRD 图3 PRELOAD 状态）
    LaunchedEffect(stack) {
        val next = stack.getOrNull(1)
        if (next?.isVideo == true) pool.preload(next)
    }
    // 消费手势层攒的位移（拖拽跟手）：手势层已改回协程 snapTo，这里只作兜底清零
    LaunchedEffect(topState) {
        snapshotFlow { Pair(topState.pendingDX, topState.pendingDY) }
            .collect { _: Pair<Float, Float> ->
                topState.drainPending()
            }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth(if (stack.firstOrNull()?.isVideo == true) 1f else 0.86f)
                .then(
                    if (stack.firstOrNull()?.isVideo == true)
                        Modifier.fillMaxHeight(0.86f)
                    else Modifier.aspectRatio(0.75f)
                )
                .onSizeChanged { with(density) { topState.cardWidthPx = it.width.toFloat() } },
        ) {
            stack.asReversed().forEachIndexed { backIdx, item ->
                val i = stack.size - 1 - backIdx   // 0 = 顶层
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
                        )                    } else {
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
        Spacer(Modifier.height(10.dp))
        Text(
            hintOf(vm),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 在组合上下文取 Context（供回调内分享用） */
@Composable
private fun contextForShare(): android.content.Context =
    androidx.compose.ui.platform.LocalContext.current

@Composable
private fun hintOf(vm: MainViewModel): String {
    return if (vm.feedFilter.value == MediaFilter.VIDEOS)
        "左滑删除 · 右滑跳过 · 上滑/双击收藏 · 长按2x"
    else
        "左滑删除 · 右滑跳过 · 上滑喜欢"
}

@Composable
private fun MediaCard(
    item: MediaItem,
    modifier: Modifier,
    overlayProgress: () -> Float,
) {
    Box(modifier.clip(RoundedCornerShape(24.dp))) {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 渐变底栏：日期 + 大小
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    formatDateCn(item.dateTaken),
                    color = Color.White,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatSizeCn(item.size),
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }
        // 左红右绿描边（随进度淡入）
        val p = overlayProgress().coerceIn(0f, 1f)
        if (p > 0.02f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = p * 0.08f)),
            )
        }
    }
}

@Composable
private fun SmallFab(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp, shadowElevation = 3.dp) {
        Box(
            Modifier
                .size(52.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { icon() }
    }
}

@Composable
private fun BigTrashButton(count: Int, size: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = count > 0,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (count > 0) "删除 $count 项 · $size" else "删除")
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

