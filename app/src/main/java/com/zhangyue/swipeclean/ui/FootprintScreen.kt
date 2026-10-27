package com.zhangyue.swipeclean.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhangyue.swipeclean.MainViewModel
import com.zhangyue.swipeclean.FootprintEngine

/** 足迹页：省份聚合（MapKit 式地图视图留待 v2，先上省份卡列表） */
@Composable
fun FootprintScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val list by vm.footprint.collectAsState()
    val total by vm.footTotal.collectAsState()

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("足迹", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (list.isEmpty()) "还没有带位置的回忆"
                    else "去过 ${list.size} 个省份 · $total 条回忆",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("拍照时打开定位，省份会自动出现在这里", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val maxCount = list.maxOf { it.count }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.zh }) { p ->
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.zh, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${p.count} 张照片 · ${p.count * 100 / maxOf(total, 1)}%",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { p.count.toFloat() / maxCount },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
