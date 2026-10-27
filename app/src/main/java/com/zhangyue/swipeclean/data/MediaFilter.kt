package com.zhangyue.swipeclean.data

enum class MediaFilter(val label: String, val videoOnly: Boolean = false) {
    PHOTOS("照片"),
    VIDEOS("视频", videoOnly = true),
    MIXED("混合"),
    SCREENSHOTS("截屏"),
    FAVORITES("喜欢"),
}
