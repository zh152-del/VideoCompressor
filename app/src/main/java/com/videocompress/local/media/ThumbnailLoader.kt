package com.videocompress.local.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

/**
 * 系统视频缩略图加载器。
 *
 * 直接复用 Android 系统已经为相册生成的缩略图（MediaStore），
 * 不引入任何第三方视频解码库，也不读取视频正文内容。
 * 任何异常都吞掉并返回 null，调用方据此显示默认图标，
 * 绝不影响扫描 / 选择 / 压缩 / 删除等核心流程。
 */
object ThumbnailLoader {

    /**
     * 加载 [uri] 对应的系统缩略图。
     *
     * - Android 10+：使用 [ContentResolver.loadThumbnail]，由系统按需生成/缓存。
     * - Android 9 及以下：使用 [MediaStore.Video.Thumbnails.getThumbnail]（按媒体 id 取 MINI_KIND）。
     *
     * @return 缩略图 [Bitmap]，失败或不可用返回 null。
     */
    fun load(context: Context, uri: Uri, width: Int = 256, height: Int = 256): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(width, height), null)
            } else {
                val id = ContentUris.parseId(uri)
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
