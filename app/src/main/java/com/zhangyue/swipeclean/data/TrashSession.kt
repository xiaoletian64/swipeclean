package com.zhangyue.swipeclean.data

import android.app.PendingIntent
import android.content.Context
import android.content.IntentSender
import android.provider.MediaStore
import kotlinx.coroutines.launch

/**
 * 一轮整理会话：当前队列、本轮标记、撤销栈。
 * 删除 = MediaStore.createTrashRequest（系统确认弹窗 → 项目进系统"最近删除"30 天）。
 */
class TrashSession(
    private val context: Context,
    val repo: MediaRepository,
) {
    val pending = LinkedHashMap<Long, MediaItem>()   // 待删除
    val liked = LinkedHashMap<Long, MediaItem>()     // 本轮新收藏
    private val undoStack = ArrayDeque<UndoOp>()     // 撤销栈
    /** 收藏落库走外部协程（调用方 scope） */
    var likeScope: kotlinx.coroutines.CoroutineScope? = null

    sealed class UndoOp {
        data class UnDelete(val item: MediaItem) : UndoOp()
        data class UnLike(val item: MediaItem) : UndoOp()
    }

    val pendingCount: Int get() = pending.size
    val pendingBytes: Long get() = pending.values.sumOf { it.size }

    fun mark(item: MediaItem) {
        pending[item.id] = item
        undoStack.addLast(UndoOp.UnDelete(item))
    }

    fun like(item: MediaItem) {
        liked[item.id] = item
        undoStack.addLast(UndoOp.UnLike(item))
        likeScope?.launch { repo.setFavorite(item, true) }
    }

    /** 撤销上一步（标记或收藏） */
    fun undo(): Boolean {
        return when (val op = undoStack.removeLastOrNull()) {
            is UndoOp.UnDelete -> { pending.remove(op.item.id); true }
            is UndoOp.UnLike -> {
                liked.remove(op.item.id)
                likeScope?.launch { repo.setFavorite(op.item, false) }
                true
            }
            null -> false
        }
    }

    /**
     * 提交删除：返回系统确认 PendingIntent 的 IntentSender，由 Activity 启动。
     * 用户确认后项目进入系统回收站；未确认则保持原状。
     */
    fun buildTrashIntent(): IntentSender? {
        if (pending.isEmpty()) return null
        val uris = pending.values.map { it.uri }
        return try {
            val pi = MediaStore.createTrashRequest(context.contentResolver, uris, true)
            pi.intentSender
        } catch (e: Exception) {
            null
        }
    }

    /** 用户确认后清空本轮标记 */
    fun onTrashConfirmed() {
        pending.clear()
        undoStack.clear()
    }

    fun roundSummary(): String =
        "待删 ${pending.size} · 预计释放 ${repo.formatSize(pendingBytes)}"
}

