package com.zhangyue.swipeclean.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MediaStore 仓库：加载、软删除（对齐系统回收站）、收藏 */
class MediaRepository(private val context: Context) {

    fun load(filter: MediaFilter, limit: Int = 4000): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        // Files 表统一查（图+视频一张表搞定，列都存在）
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
        )
        val type = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1, 3)"
        val sel: String
        val args: Array<String>?
        val orderBy: String
        when (filter) {
            MediaFilter.PHOTOS -> { sel = "$type AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} = 1"; args = null }
            MediaFilter.VIDEOS -> { sel = "$type AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} = 3"; args = null }
            MediaFilter.MIXED -> { sel = type; args = null }
            MediaFilter.SCREENSHOTS -> {
                sel = "$type AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
                args = arrayOf("%Screenshot%")
            }
            MediaFilter.FAVORITES -> { sel = "$type AND ${MediaStore.Files.FileColumns.IS_FAVORITE} = 1"; args = null }
        }
        orderBy = if (filter == MediaFilter.VIDEOS) {
            // 视频轮支持大文件优先（免费提供，对标品收费项）
            "${MediaStore.Files.FileColumns.SIZE} DESC, ${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"
        } else {
            "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC, ${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        }
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection, sel, args, orderBy
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
            val iW = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
            val iFav = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            while (c.moveToNext() && out.size < limit) {
                val taken = c.getLong(iTaken).takeIf { it > 0 } ?: c.getLong(iMod)
                val path = c.getString(iPath) ?: ""
                out.add(
                    MediaItem(
                        id = c.getLong(iId),
                        isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                        dateTaken = taken,
                        size = c.getLong(iSize),
                        durationMs = c.getLong(iDur),
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        isScreenshot = path.contains("Screenshot", true),
                        isFavorite = c.getInt(iFav) == 1,
                    )
                )
            }
        }
        return out
    }

    /** 视频缩略图（封面帧）uri：MediaStore 自带 micro 缩略图，ContentResolver 触发生成 */
    fun videoThumbUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Video.Thumbnails.EXTERNAL_CONTENT_URI, id)

    /** 全量图片（相似扫描用），按日期倒序 */
    fun loadImagesForScan(limit: Int = 8000): List<MediaItem> {
        return load(MediaFilter.PHOTOS, limit)
    }

    suspend fun setFavorite(item: MediaItem, fav: Boolean) = withContext(Dispatchers.IO) {
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.IS_FAVORITE, if (fav) 1 else 0)
        }
        runCatching { context.contentResolver.update(item.uri, values, null, null) }
    }

    fun formatDate(sec: Long): String {
        if (sec <= 0) return "拍摄日期未知"
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = sec * 1000 }
        return "${cal.get(java.util.Calendar.YEAR)}/${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1 shl 10 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }

    companion object {
        fun mediaTypeFilterExtra(): String =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3)"
    }
}
