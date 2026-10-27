package com.zhangyue.swipeclean

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangyue.swipeclean.data.MediaFilter
import com.zhangyue.swipeclean.data.MediaItem
import com.zhangyue.swipeclean.data.MediaRepository
import com.zhangyue.swipeclean.data.Prefs
import com.zhangyue.swipeclean.data.TrashSession
import com.zhangyue.swipeclean.logic.SimilarGroup
import com.zhangyue.swipeclean.logic.SimilarScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    private lateinit var appContext: Context
    private lateinit var activity: Activity

    lateinit var repo: MediaRepository
        private set
    lateinit var session: TrashSession
        private set
    private lateinit var scanner: SimilarScanner
    private val prefs by lazy { Prefs(appContext) }

    // 授权就绪
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    // 整理轮
    private val _feed = MutableStateFlow<List<MediaItem>>(emptyList())
    val feed: StateFlow<List<MediaItem>> = _feed
    val feedFilter = MutableStateFlow(MediaFilter.PHOTOS)
    val roundSize = MutableStateFlow(30)      // 照片轮 30；视频轮 15；混合轮 20+10
    val roundIndex = MutableStateFlow(0)      // 本轮已看几张
    val undoTick = MutableStateFlow(0)        // 撤销后刷新视图
    val videoUnmuted = MutableStateFlow(false) // 视频声音开关（默认静音，记忆偏好）

    // 相似
    private val _groups = MutableStateFlow<List<SimilarGroup>>(emptyList())
    val groups: StateFlow<List<SimilarGroup>> = _groups
    val scanProgress = MutableStateFlow(-1)   // -1 未扫
    val scanTotal = MutableStateFlow(0)
    val scanYear = MutableStateFlow("")

    // 足迹
    private val _footprint = MutableStateFlow<List<FootprintEngine.ProvCount>>(emptyList())
    val footprint: StateFlow<List<FootprintEngine.ProvCount>> = _footprint
    val footTotal = MutableStateFlow(0)

    fun bind(activity: Activity) {
        this.activity = activity
        this.appContext = activity.applicationContext
    }

    fun onPermissionGranted() {
        if (_ready.value) return
        repo = MediaRepository(appContext)
        session = TrashSession(appContext, repo)
        session.likeScope = viewModelScope
        scanner = SimilarScanner(appContext, repo)
        _ready.value = true
        viewModelScope.launch { loadFeed() }
        viewModelScope.launch { loadFootprint() }
        viewModelScope.launch { videoUnmuted.value = prefs.unmutedSnapshot() }
    }

    // ---------- 整理轮 ----------

    fun setFilter(f: MediaFilter) {
        feedFilter.value = f
        viewModelScope.launch { loadFeed() }
    }

    private suspend fun loadFeed() {
        val all = withContext(Dispatchers.IO) { repo.load(feedFilter.value) }
        _feed.value = when (feedFilter.value) {
            MediaFilter.VIDEOS -> all.take(15)
            MediaFilter.MIXED -> {
                // 混合轮：20 照片 + 10 视频，按拍摄时间交错（视频每 2 张插 1 个）
                val photos = all.filter { !it.isVideo }.take(20)
                val videos = all.filter { it.isVideo }.take(10)
                buildList {
                    val pi = photos.iterator()
                    var vIdx = 0
                    while (pi.hasNext() || vIdx < videos.size) {
                        repeat(2) { if (pi.hasNext()) add(pi.next()) }
                        if (vIdx < videos.size) add(videos[vIdx++])
                    }
                }
            }
            else -> all.take(roundSize.value)
        }
        roundIndex.value = 0
    }

    fun nextRound() {
        viewModelScope.launch { loadFeed() }
    }

    fun onSwipe(dir: com.zhangyue.swipeclean.ui.SwipeDir, item: MediaItem) {
        when (dir) {
            com.zhangyue.swipeclean.ui.SwipeDir.LEFT -> session.mark(item)
            com.zhangyue.swipeclean.ui.SwipeDir.UP -> session.like(item)
            else -> {}
        }
        roundIndex.value += 1
    }

    /** 双击收藏（与上滑同语义：收藏并推进轮次） */
    fun onLikeTap(item: MediaItem) {
        session.like(item)
        roundIndex.value += 1
    }

    /** 视频声音开关（PRD F5：默认静音，记忆偏好） */
    fun toggleMute() {
        videoUnmuted.value = !videoUnmuted.value
        viewModelScope.launch { prefs.setVideoUnmuted(videoUnmuted.value) }
    }

    fun undo() {
        session.undo()
        undoTick.value += 1
        if (roundIndex.value > 0) roundIndex.value -= 1
    }

    fun buildTrashIntent(): android.content.IntentSender? = session.buildTrashIntent()

    /** 相似页按勾选集合生成系统删除确认 */
    fun buildTrashIntentFor(items: List<MediaItem>): android.content.IntentSender? {
        if (items.isEmpty()) return null
        return try {
            android.provider.MediaStore.createTrashRequest(
                appContext.contentResolver, items.map { it.uri }, true
            ).intentSender
        } catch (e: Exception) { null }
    }

    fun onTrashConfirmed() {
        viewModelScope.launch {
            val ids = session.pending.values.map { it.id }
            prefs.addCleared(ids)
            session.onTrashConfirmed()
            loadFeed()
        }
    }

    // ---------- 相似 ----------

    fun startScan() {
        if (scanProgress.value in 0..Int.MAX_VALUE && scanProgress.value < scanTotal.value) return
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { repo.loadImagesForScan(4000) }
            scanTotal.value = items.size
            scanProgress.value = 0
            val result = scanner.scan(items) { done, total ->
                scanProgress.value = done
                scanTotal.value = total
            }
            val bl = prefs.blacklist.first().toSet()
            _groups.value = result.filter { g ->
                bl.none { key -> g.items.any { it.id.toString() == key } }
            }
            scanYear.value = "全部"
        }
    }

    fun ignoreGroup(g: SimilarGroup) {
        _groups.value = _groups.value - g
        viewModelScope.launch {
            g.items.forEach { prefs.addBlacklist(it.id.toString()) }
        }
    }

    /** 智能清理：每组勾选非最优张 */
    fun smartCleanSelection(groups: List<SimilarGroup>): List<MediaItem> =
        groups.flatMap { g -> g.items.filter { it.id != g.best.id } }

    // ---------- 足迹 ----------

    private suspend fun loadFootprint() {
        val result = withContext(Dispatchers.IO) { FootprintEngine.analyze(appContext, repo) }
        _footprint.value = result
        footTotal.value = result.sumOf { it.count }
    }
}
