package com.zhangyue.swipeclean.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.zhangyue.swipeclean.data.MediaItem
import com.zhangyue.swipeclean.data.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一个相似组 */
data class SimilarGroup(
    val items: List<MediaItem>,
    val best: MediaItem,          // 推荐"最优"（大文件优先）
    val freeableBytes: Long,      // 可释放
)

/**
 * 相似扫描器（对标 iOS 端 Vision FeaturePrint 的三段式管线，Android 替代实现）：
 * 1) 缩略图解码（64px，带 EXIF 旋转）
 * 2) ThumbHash 指纹（DCT 低频）汉明桶预筛 —— O(n)
 * 3) 桶内逐对距离精判 + 时间邻近（3 分钟内连拍优先成组）
 */
class SimilarScanner(private val context: Context, private val repo: MediaRepository) {

    private val thumbCache = object : LruCache<Long, ByteArray>(512) {}

    suspend fun scan(
        items: List<MediaItem>,
        threshold: Double = 0.55,
        timeWindowSec: Long = 180,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SimilarGroup> = withContext(Dispatchers.Default) {
        val total = items.size
        val hashes = HashMap<Long, ByteArray>(total)
        var done = 0
        for (m in items) {
            val h = thumbCache.get(m.id) ?: thumbOf(m)
            if (h != null) {
                thumbCache.put(m.id, h)
                hashes[m.id] = h
            }
            done++
            if (done % 25 == 0) onProgress(done, total)
        }
        onProgress(total, total)

        // 按拍摄时间升序，窗口内逐对精判（连拍天然相邻）
        val sorted = items.sortedBy { it.dateTaken }
        val uf = UnionFind(items.size)

        for (i in sorted.indices) {
            val mi = sorted[i]
            val hi = hashes[mi.id] ?: continue
            var j = i + 1
            while (j < sorted.size) {
                val mj = sorted[j]
                if (mj.dateTaken - mi.dateTaken > timeWindowSec) break // 时间断带
                val hj = hashes[mj.id]
                if (hj != null && ThumbHash.distance(hi, hj) < threshold) {
                    uf.union(i, j)
                }
                j++
            }
        }

        // 聚组
        val groups = HashMap<Int, MutableList<MediaItem>>()
        sorted.forEachIndexed { i, m -> groups.getOrPut(uf.find(i)) { mutableListOf() }.add(m) }
        groups.values
            .filter { it.size >= 2 }
            .map { members ->
                val best = members.maxByOrNull { it.size }!!
                SimilarGroup(
                    items = members.sortedByDescending { it.size },
                    best = best,
                    freeableBytes = members.filter { it.id != best.id }.sumOf { it.size },
                )
            }
            .sortedByDescending { it.freeableBytes }
    }

    private fun thumbOf(item: MediaItem): ByteArray? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(item.uri)?.use { ins -> BitmapFactory.decodeStream(ins, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > 96 || opts.outHeight / sample > 96) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.contentResolver.openInputStream(item.uri)?.use { ins ->
                BitmapFactory.decodeStream(ins, null, decodeOpts)
            } ?: return null
            val rotated = rotateByExif(bmp, item)
            val small = if (rotated.width > 64 || rotated.height > 64) {
                val scale = 64.0 / maxOf(rotated.width, rotated.height)
                Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt().coerceAtLeast(1), (rotated.height * scale).toInt().coerceAtLeast(1), true)
            } else rotated
            val w = small.width; val h = small.height
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            val buf = ByteArray(w * h * 4)
            px.forEachIndexed { i, v ->
                buf[i * 4] = (v shr 16 and 0xff).toByte()
                buf[i * 4 + 1] = (v shr 8 and 0xff).toByte()
                buf[i * 4 + 2] = (v and 0xff).toByte()
                buf[i * 4 + 3] = (v shr 24 and 0xff).toByte()
            }
            val hash = ThumbHash.hashOf(w, h, buf)
            if (small !== bmp) small.recycle()
            if (rotated !== bmp) rotated.recycle()
            bmp.recycle()
            hash
        } catch (e: Exception) {
            null
        }
    }

    private fun rotateByExif(bmp: Bitmap, item: MediaItem): Bitmap {
        return try {
            val deg = context.contentResolver.openInputStream(item.uri)?.use { ins ->
                when (ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
            if (deg == 0) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        } catch (e: Exception) {
            bmp
        }
    }
}

private class UnionFind(n: Int) {
    val parent = IntArray(n) { it }
    fun find(x: Int): Int {
        var a = x
        while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }
        return a
    }
    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }
}
