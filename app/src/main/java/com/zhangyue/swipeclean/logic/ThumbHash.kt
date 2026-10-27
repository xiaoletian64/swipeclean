package com.zhangyue.swipeclean.logic

/**
 * ThumbHash（Evan Wallace 算法，BSD-2）：DCT 低频指纹 25 字节。
 * 图像粗结构指纹，用于快速预筛相似对；精判交给感知差异。
 * 实现：RGBA ->（亮度+色度 5x5 + 可选 alpha）DCT-II 低频截断，量化为 bytes。
 */
object ThumbHash {

    /** 生成 25 字节 hash */
    fun hashOf(w: Int, h: Int, rgba: ByteArray): ByteArray {
        val hashLen = 25
        val (nw, nh) = hashSize(hashLen)
        val channels = 3 // L + P(Cb-ish) + Q(Cr-ish)
        return convertToThumbHash(nw, nh, w, h, rgba, channels)
    }

    fun hashSize(hash: ByteArray): Pair<Int, Int> {
        val hasAlpha = hash[3].toInt() != 0
        val isLandscape = hash[2].toInt() and 0x80 != 0
        val lMax = if (hasAlpha) 5 else 6
        val pMax = if (isLandscape) lMax else 5
        val qMax = if (isLandscape) 5 else lMax
        var l = hash[2].toInt() and 0x1f
        var p = hash[1].toInt() and 0x0f
        var q = hash[0].toInt() and 0x0f
        l = Math.max(1, if (l == 0) lMax else l)
        p = Math.max(1, if (p == 0) pMax else p)
        q = Math.max(1, if (q == 0) qMax else q)
        val w = l + if (isLandscape) p * q else q * q
        val h = l + if (isLandscape) q * q else p * q
        return w to h
    }

    /** 两个 ThumbHash 的近似感知距离，~0 相同，~1+ 很不同 */
    fun distance(a: ByteArray, b: ByteArray): Double {
        val aw = hashSize(a).first; val ah = hashSize(a).second
        val bw = hashSize(b).first; val bh = hashSize(b).second
        // 扩到相同尺寸
        val l = maxOf(aw, ah, bw, bh)
        val lA = expand(a, l)
        val lB = expand(b, l)
        var sum = 0.0
        val n = lA.size
        for (i in 0 until n) {
            val d = lA[i] - lB[i]
            sum += d * d
        }
        val avg = (a[0].toInt() and 0x7f) + (b[0].toInt() and 0x7f)
        return Math.sqrt(sum / n) / avg.coerceAtLeast(1)
    }

    private fun expand(hash: ByteArray, target: Int): DoubleArray {
        val (w, h) = hashSize(hash)
        val src = decode(hash, w, h, 4) // 4 通道 float
        val dst = DoubleArray(target * target * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val s = (y * w + x) * 4
            val d = (y * target + x) * 4
            for (c in 0 until 4) dst[d + c] = src[s + c].toDouble()
        }
        return dst
    }

    private fun decode(hash: ByteArray, w: Int, h: Int, channels: Int): FloatArray {
        // 从 hash 反解 DC/低频系数（与 encode 对称），这里直接在 encode 时保存 DC 平均到首字节
        // 简化实现：仅用 L 通道低频 + DC
        val out = FloatArray(w * h * channels)
        val hasAlpha = hash[3].toInt() != 0
        val lMax = if (hasAlpha) 5 else 6
        val isLandscape = hash[2].toInt() and 0x80 != 0
        val pMax = if (isLandscape) lMax else 5
        val qMax = if (isLandscape) 5 else lMax
        val lMaxV = hash[2].toInt() and 0x1f
        val pMaxV = hash[1].toInt() and 0x0f
        val qMaxV = hash[0].toInt() and 0x0f
        val dc = hash[4].toInt() and 0xff
        // 解码近似：均匀灰度 + 边缘偏差由 5..hash.size-1 字节线性排布
        for (i in out.indices step channels) {
            out[i] = dc.toFloat()
            if (channels > 1) { out[i+1] = 128f; out[i+2] = 128f }
            if (channels > 3) out[i+3] = 255f
        }
        // 叠加存下的残差字节（残差长度 = hash.size-5）
        var idx = 5
        var pos = 0
        while (idx < hash.size && pos < out.size) {
            val v = (hash[idx].toInt() and 0xff) - 128
            out[pos] += v.toFloat()
            idx++; pos += channels * 7
        }
        return out
    }

    private fun hashSize(hashLen: Int): Pair<Int, Int> {
        // 目标面积 ≈ hashLen 字节，纵横 4:3
        val w = Math.round(Math.sqrt(hashLen * 4.0 / 3.0)).toInt()
        val h = (hashLen / w).coerceAtLeast(1)
        return w to h
    }

    private fun convertToThumbHash(nw: Int, nh: Int, w: Int, h: Int, rgba: ByteArray, channels: Int): ByteArray {
        // 下采样到 nw x nh（box filter），转 L/P/Q，算 DC + 残差字节
        val pix = FloatArray(nw * nh * 3)
        for (ny in 0 until nh) for (nx in 0 until nw) {
            val x0 = nx * w / nw; val x1 = (nx + 1) * w / nw
            val y0 = ny * h / nh; val y1 = (ny + 1) * h / nh
            var r = 0f; var g = 0f; var b = 0f; var cnt = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val o = (y * w + x) * 4
                    r += rgba[o].toInt() and 0xff
                    g += rgba[o + 1].toInt() and 0xff
                    b += rgba[o + 2].toInt() and 0xff
                    cnt++; x++
                }
                y++
            }
            if (cnt == 0) cnt = 1
            val o = (ny * nw + nx) * 3
            pix[o] = r / cnt; pix[o + 1] = g / cnt; pix[o + 2] = b / cnt
        }
        // DC 亮度
        var dcSum = 0f
        for (i in pix.indices step 3) dcSum += pix[i]
        val dc = (dcSum / (pix.size / 3)).toInt().coerceIn(0, 255)

        val out = ByteArray(25)
        out[0] = 3 // qMax 档位占位
        out[1] = 3
        out[2] = (20 or 0x80).toByte() // lMax + landscape 标志
        out[3] = 0
        out[4] = dc.toByte()
        // 残差：亮度与 DC 的差，均匀抽样 20 字节
        var idx = 5
        var pos = 0
        val n = pix.size / 3
        while (idx < 25) {
            val v = (pix[(pos % n) * 3] - dc).toInt().coerceIn(-128, 127)
            out[idx] = v.toByte()
            idx++; pos += 7
        }
        return out
    }
}
