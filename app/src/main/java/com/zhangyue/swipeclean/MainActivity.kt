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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
            AppScaffold(vm) { sender -> trashLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    FEED("整理", Icons.Filled.PhotoLibrary),
    SIMILAR("相似", Icons.Filled.AutoAwesome),
    FOOT("足迹", Icons.Filled.LocationOn),
    FAV("喜欢", Icons.Filled.Favorite),
}

@Composable
fun AppScaffold(vm: MainViewModel, launchTrash: (IntentSender) -> Unit) {
    var tab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Tab.FEED) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.material3.Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
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
