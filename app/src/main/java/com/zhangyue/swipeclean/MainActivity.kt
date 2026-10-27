package com.zhangyue.swipeclean

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhangyue.swipeclean.ui.FavoritesScreen
import com.zhangyue.swipeclean.ui.FeedScreen
import com.zhangyue.swipeclean.ui.FootprintScreen
import com.zhangyue.swipeclean.ui.SimilarScreen

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val trashLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) vm.onTrashConfirmed()
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) vm.onPermissionGranted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.bind(this)
        // 版本化媒体权限：Android 13+ 用 READ_MEDIA_*，12 及以下用 READ_EXTERNAL_STORAGE
        val perms = if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        ) else arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        val need = perms.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) vm.onPermissionGranted() else permLauncher.launch(need.toTypedArray())

        setContent {
            // vc5：全局纯黑媒体主题（看内容的地方，黑最中性；白=主操作；红=仅删除语义）
            MaterialTheme(colorScheme = AppColors.dark) {
                AppScaffold(vm) { sender -> trashLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            }
        }
    }
}

/** vc5 全局色板：黑底 / 白主操作 / 红仅删除 */
object AppColors {
    val dark = androidx.compose.material3.darkColorScheme(
        background = Color(0xFF0A0A0A),
        onBackground = Color(0xFFF8FAFC),
        surface = Color(0xFF141414),
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = Color(0xFF1F1F23),
        onSurfaceVariant = Color(0xFF9CA3AF),
        primary = Color(0xFFF8FAFC),
        onPrimary = Color(0xFF0A0A0A),
        secondary = Color(0xFFE11D48),
    )
}

private enum class Tab(val label: String, val iconFilled: ImageVector, val iconOutlined: ImageVector) {
    FEED("整理", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary),
    SIMILAR("相似", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    FOOT("足迹", Icons.Filled.LocationOn, Icons.Outlined.LocationOn),
    FAV("喜欢", Icons.Filled.Favorite, Icons.Filled.FavoriteBorder),
}

@Composable
fun AppScaffold(vm: MainViewModel, launchTrash: (IntentSender) -> Unit) {
    var tab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Tab.FEED) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF141414)) {
                Tab.entries.forEach { t ->
                    val selected = tab == t
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                if (selected) t.iconFilled else t.iconOutlined,
                                contentDescription = t.label,
                            )
                        },
                        label = {
                            Text(
                                t.label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFF8FAFC),
                            selectedTextColor = Color(0xFFF8FAFC),
                            unselectedIconColor = Color(0xFF6B7280),
                            unselectedTextColor = Color(0xFF6B7280),
                            indicatorColor = Color(0xFF2A2A30),
                        ),
                    )
                }
            }
        }
    ) { pad ->
        val modifier = Modifier.padding(pad)
        when (tab) {
            Tab.FEED -> FeedScreen(vm, scope, modifier, launchTrash)
            Tab.SIMILAR -> SimilarScreen(vm, scope, modifier, launchTrash)
            Tab.FOOT -> FootprintScreen(vm, modifier)
            Tab.FAV -> FavoritesScreen(vm, modifier)
        }
    }
}
