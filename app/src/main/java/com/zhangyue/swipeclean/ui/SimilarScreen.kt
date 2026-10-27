package com.zhangyue.swipeclean.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zhangyue.swipeclean.MainViewModel
import com.zhangyue.swipeclean.logic.SimilarGroup
import kotlinx.coroutines.CoroutineScope

/** 相似清理页：扫描进度 → 分组卡片（勾选删除/忽略） */
@Composable
fun SimilarScreen(
    vm: MainViewModel,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    launchTrash: (android.content.IntentSender) -> Unit,
) {
    val groups by vm.groups.collectAsState()
    val progress by vm.scanProgress.collectAsState()
    val total by vm.scanTotal.collectAsState()
    // 每组被取消勾选的 id
    val excluded = remember { mutableStateMapOf<Long, MutableSet<Long>>() }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("相似照片", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (groups.isEmpty()) "寻找构图、颜色和内容相近的照片"
                    else "${groups.size} 组 · ${groups.sumOf { it.items.size - 1 }} 张可清理",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { vm.startScan() },
                enabled = progress < 0 || progress >= total,
            ) {
                Text(if (progress in 0 until total) "扫描中…" else if (groups.isEmpty()) "开始扫描" else "重新扫描")
            }
        }

        when {
            progress in 0 until total -> {
                Column(Modifier.padding(24.dp)) {
                    Text("正在分析… $progress / $total", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.toFloat() / total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            groups.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("从最近的照片开始，逐批检查相似项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(groups, key = { it.best.id }) { g ->
                        GroupCard(
                            group = g,
                            excluded = excluded[g.best.id] ?: emptySet(),
                            onToggle = { id ->
                                val set = excluded.getOrPut(g.best.id) { mutableSetOf() }
                                if (!set.add(id)) set.remove(id)
                            },
                            onIgnore = { vm.ignoreGroup(g) },
                            onDeleteChecked = { sender -> launchTrash(sender) },
                            buildIntent = {
                                val checked = g.items.filter { it.id != g.best.id && (excluded[g.best.id]?.contains(it.id) != true) }
                                vm.buildTrashIntentFor(checked)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: SimilarGroup,
    excluded: Set<Long>,
    onToggle: (Long) -> Unit,
    onIgnore: () -> Unit,
    onDeleteChecked: (android.content.IntentSender) -> Unit,
    buildIntent: () -> android.content.IntentSender?,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("最多可释放 ${formatSizeCn(group.freeableBytes)}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("${group.items.size} 张相似", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onIgnore) { Text("忽略此项重复", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(10.dp))
            // 缩略图行
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.items.take(5).forEach { m ->
                    val isBest = m.id == group.best.id
                    val off = !isBest && excluded.contains(m.id)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.8f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onToggle(m.id) }
                            .then(
                                if (isBest) Modifier.border(2.dp, Color(0xFF22C55E), RoundedCornerShape(12.dp))
                                else if (off) Modifier.border(2.dp, Color(0xFFE5E7EB), RoundedCornerShape(12.dp))
                                else Modifier.border(2.dp, Color(0xFFE11D48), RoundedCornerShape(12.dp))
                            ),
                    ) {
                        AsyncImage(m.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (isBest) {
                            Surface(
                                color = Color(0xFF22C55E),
                                shape = RoundedCornerShape(bottomEnd = 12.dp),
                                modifier = Modifier.align(Alignment.TopStart),
                            ) {
                                Text("★ 最优", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
            if (group.items.size > 5) {
                Text(
                    "…还有 ${group.items.size - 5} 张（取消勾选=保留）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            val checkedCount = group.items.count { it.id != group.best.id && !excluded.contains(it.id) }
            Button(
                onClick = { buildIntent()?.let(onDeleteChecked) },
                enabled = checkedCount > 0,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("删除选中 $checkedCount 张")
            }
        }
    }
}
