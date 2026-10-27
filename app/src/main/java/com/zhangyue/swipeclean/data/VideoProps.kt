package com.zhangyue.swipeclean.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** 一条媒体记录（照片或视频） */
data class MediaItem(
    val id: Long,
    val isVideo: Boolean,
    val dateTaken: Long,      // 秒
    val size: Long,           // 字节
    val durationMs: Long,     // 视频时长
    val width: Int,           // 视频/图片宽（0=未知）
    val height: Int,          // 视频/图片高
    val isScreenshot: Boolean,
    val isFavorite: Boolean,
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id
        )
}
