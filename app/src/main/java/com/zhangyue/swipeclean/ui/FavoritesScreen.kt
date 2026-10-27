package com.zhangyue.swipeclean.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zhangyue.swipeclean.MainViewModel

/** 喜欢页：系统收藏的媒体（整理时上滑加入） */
@Composable
fun FavoritesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ready by vm.ready.collectAsState()
    val refresh by vm.undoTick.collectAsState()
    val favs = remember(ready, refresh) {
        if (!ready) emptyList() else vm.repo.load(com.zhangyue.swipeclean.data.MediaFilter.FAVORITES)
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Column {
                Text("喜欢", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${favs.size} 项 · 整理时上滑或点爱心加入",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (favs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("当前没有喜欢的照片", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(2.dp),
            ) {
                items(favs, key = { it.id }) { m ->
                    AsyncImage(
                        m.uri,
                        null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(1.dp),
                    )
                }
            }
        }
    }
}
