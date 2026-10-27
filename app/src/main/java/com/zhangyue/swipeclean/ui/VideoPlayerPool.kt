package com.zhangyue.swipeclean.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.zhangyue.swipeclean.data.MediaItem as PhotoItem

/**
 * 双实例播放器池（PRD 图3）：A 播当前、B 预载下一张。
 * 释放纪律：release() 由 DisposableEffect/onDispose 或 releaseAll() 触发。
 */
class VideoPlayerPool(context: Context, audioManager: AudioManager?) {
    private val appContext = context.applicationContext
    private val am = audioManager

    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var currentId: Long = -1L
    private var preloadId: Long = -1L

    var muted: Boolean = true
        set(value) {
            field = value
            playerA?.volume = if (value) 0f else 1f
            playerB?.volume = if (value) 0f else 1f
            if (!value) requestFocus() else abandonFocus()
        }

    private var focusRequest: AudioFocusRequest? = null

    private fun build(): ExoPlayer {
        val p = ExoPlayer.Builder(appContext).build()
        p.volume = if (muted) 0f else 1f
        p.repeatMode = Player.REPEAT_MODE_ONE   // 循环
        p.playWhenReady = false
        return p
    }

    private fun requestFocus() {
        if (am == null || focusRequest != null) return
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .build()
        am.requestAudioFocus(focusRequest!!)
    }

    private fun abandonFocus() {
        focusRequest?.let { am?.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** 顶层卡起播：优先用已预载的 B（首帧已备），否则现开 A */
    fun play(item: PhotoItem, surface: SurfaceView?): ExoPlayer {
        val p: ExoPlayer = if (item.id == preloadId) {
            val b = playerB ?: build()
            playerB = null; preloadId = -1L
            playerA?.release()
            playerA = b
            b
        } else {
            playerA?.release()
            build().also { playerA = it }
        }
        currentId = item.id
        p.setMediaItem(MediaItem.fromUri(item.uri.toString()))
        p.prepare()
        surface?.let { p.setVideoSurfaceView(it) }
        p.playWhenReady = true
        return p
    }

    /** 预载后层第 1 张（不挂 surface，不渲染，PRD 图3 PRELOAD 状态） */
    fun preload(item: PhotoItem) {
        if (item.id == currentId || item.id == preloadId) return
        playerB?.release()
        val b = build()
        playerB = b
        preloadId = item.id
        b.setMediaItem(MediaItem.fromUri(item.uri.toString()))
        b.prepare()
    }

    /** 滑出/覆盖：即刻停（FLY_OUT 状态） */
    fun pauseCurrent() {
        playerA?.playWhenReady = false
    }

    fun resumeCurrent() {
        playerA?.playWhenReady = true
    }

    fun seekTo(positionMs: Long) {
        playerA?.seekTo(positionMs)
    }

    /** 兜底重挂 surface（重组复用时） */
    fun attachSurface(view: SurfaceView) {
        playerA?.setVideoSurfaceView(view)
    }

    val isPlaying: Boolean get() = playerA?.isPlaying == true
    val currentPosition: Long get() = playerA?.currentPosition ?: 0L
    val duration: Long get() = playerA?.duration?.takeIf { it > 0 } ?: 0L
    val playbackSpeed: Float get() = playerA?.playbackParameters?.speed ?: 1f

    fun setSpeed(speed: Float) {
        playerA?.let {
            it.playbackParameters = it.playbackParameters.withSpeed(speed)
        }
    }

    /** 生命周期挂起：全停不释放（回来还在这张卡） */
    fun stopAll() {
        pauseCurrent()
        playerB?.playWhenReady = false
        abandonFocus()
    }

    /** 全量释放（轮结束/切筛选/销毁） */
    fun releaseAll() {
        playerA?.release(); playerA = null
        playerB?.release(); playerB = null
        currentId = -1L; preloadId = -1L
        abandonFocus()
    }
}

/** Compose 侧：按卡生命周期持有池，卡销毁自动 release（释放纪律 ①） */
@Composable
fun rememberVideoPlayerPool(): VideoPlayerPool {
    val context = androidx.compose.ui.platform.LocalContext.current
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val pool = remember { VideoPlayerPool(context, am) }
    DisposableEffect(Unit) {
        onDispose { pool.releaseAll() }
    }
    return pool
}
