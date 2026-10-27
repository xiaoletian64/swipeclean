package com.zhangyue.swipeclean.ui

/**
 * 手势调试探针：仲裁判定计数（redroid 排查用，正式发布前整体移除本文件与两处引用）。
 */
object GestureDebug {
    var downs: Int = 0
    var drags: Int = 0
    var ups: Int = 0
    var longPress: Int = 0
    var maxDist: Float = 0f

    fun snapshot(): String = "downs=$downs drags=$drags ups=$ups longPress=$longPress maxDist=${maxDist.toInt()}"
    fun reset() { downs = 0; drags = 0; ups = 0; longPress = 0; maxDist = 0f }
}
