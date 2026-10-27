package com.zhangyue.swipeclean

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import com.zhangyue.swipeclean.data.MediaFilter
import com.zhangyue.swipeclean.data.MediaItem
import com.zhangyue.swipeclean.data.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 足迹引擎：照片 EXIF GPS -> 省份归属（内置 cn-atlas 简化边界，点在多边形内测试） */
object FootprintEngine {

    @Serializable
    data class Province(
        val id: String,
        val name: String,
        val zh: String,
        val center: List<Double>,
        val bbox: List<Double>,
        val rings: List<List<List<Double>>>,
    )

    data class ProvCount(
        val zh: String,
        val name: String,
        val count: Int,
        val lastDate: Long,   // 最近一张（秒）
        val center: List<Double>,
    )

    private var loaded: List<Province>? = null
    private var appCtx: Context? = null

    private fun loadProvinces(ctx: Context): List<Province> {
        loaded?.let { return it }
        val text = ctx.assets.open("footprint_provinces.json").bufferedReader().use { it.readText() }
        val list = Json { ignoreUnknownKeys = true }.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Province.serializer()), text)
        loaded = list
        return list
    }

    suspend fun analyze(ctx: Context, repo: MediaRepository): List<ProvCount> = withContext(Dispatchers.IO) {
        appCtx = ctx
        val provinces = loadProvinces(ctx)
        val items = repo.load(MediaFilter.PHOTOS, 6000) + repo.load(MediaFilter.VIDEOS, 3000)
        val counts = HashMap<String, Int>()
        val last = HashMap<String, Long>()
        for (item in items) {
            val ll = gpsOf(item) ?: continue
            val prov = provinces.firstOrNull { pointIn(it, ll.first, ll.second) } ?: continue
            counts.merge(prov.zh, 1, Int::plus)
            last.merge(prov.zh, item.dateTaken) { a, b -> maxOf(a, b) }
        }
        provinces.mapNotNull { p ->
            counts[p.zh]?.let { c ->
                ProvCount(p.zh, p.name, c, last[p.zh] ?: 0L, p.center)
            }
        }.sortedByDescending { it.count }
    }

    /** 读照片 EXIF GPS（度分转十进制） */
    private val gpsCache = HashMap<Long, Pair<Double, Double>?>()

    private fun gpsOf(item: MediaItem): Pair<Double, Double>? {
        if (item.isVideo) return null
        gpsCache[item.id]?.let { return it }
        val result: Pair<Double, Double>? = try {
            appCtx!!.contentResolver.openInputStream(item.uri)?.use { ins ->
                val exif = ExifInterface(ins)
                val latDbl = exif.latLong
                if (latDbl != null) latDbl[0] to latDbl[1] else null
            }
        } catch (e: Exception) {
            null
        }
        gpsCache[item.id] = result
        return result
    }

    fun pointIn(p: Province, lon: Double, lat: Double): Boolean {
        val b = p.bbox
        if (lon < b[0] || lon > b[2] || lat < b[1] || lat > b[3]) return false
        for (ring in p.rings) {
            if (inRing(ring, lon, lat)) return true
        }
        return false
    }

    /** 射线法 */
    private fun inRing(ring: List<List<Double>>, x: Double, y: Double): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val xi = ring[i][0]; val yi = ring[i][1]
            val xj = ring[j][0]; val yj = ring[j][1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
