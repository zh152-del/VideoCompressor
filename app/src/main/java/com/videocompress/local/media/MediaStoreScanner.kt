package com.videocompress.local.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.database.getLongOrNull
import com.videocompress.local.util.AppLog

/** 扫描到的视频元数据（不含任何二进制数据） */
data class ScannedVideo(
    val uri: Uri,
    val uriString: String,
    val displayName: String,
    val size: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val relativePath: String?
)

/**
 * 系统相册视频扫描。
 *
 * 明确不使用硬编码目录（/DCIM、/Movies）遍历，
 * 一律走 MediaStore.Video.Media 查询，保证不同厂商目录结构都能覆盖。
 * 也刻意没有使用一次只能选单个视频的 Photo Picker —— 本项目的核心是「自动识别全部视频」。
 */
object MediaStoreScanner {

    /**
     * 本应用自己的输出目录标记。
     *
     * 如果不排除它，压缩产物（xxx_compressed.mp4）会被当成「新视频」再次扫进任务队列，
     * 于是对已经压过的文件反复重编码：画质被多次劣化，还会不断产生
     * _compressed_1、_compressed_2 ... 的雪球文件。必须挡在扫描这一层。
     */
    private const val OWN_OUTPUT_DIR_MARK = "VideoCompressor"

    /** 本应用输出文件的命名标记，用于文件被用户移出目录后的兜底识别 */
    private const val OWN_OUTPUT_NAME_MARK = "_compressed"

    /** 判断某个视频是不是本应用自己产出的压缩结果 */
    private fun isOwnOutput(displayName: String?, relativePath: String?, dataPath: String?): Boolean {
        val path = relativePath ?: dataPath
        if (path != null && path.contains(OWN_OUTPUT_DIR_MARK, ignoreCase = true)) return true
        val name = displayName ?: return false
        return name.contains(OWN_OUTPUT_NAME_MARK, ignoreCase = true)
    }

    fun scan(context: Context): List<ScannedVideo> {
        AppLog.i("SCAN_START", "开始扫描系统相册视频")
        val result = mutableListOf<ScannedVideo>()
        val startTime = System.currentTimeMillis()

        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DATE_ADDED)
            add(MediaStore.Video.Media.DATE_MODIFIED)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            add(MediaStore.Video.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Video.Media.RELATIVE_PATH)
            }
            // 绝对路径：API 29 以下没有 RELATIVE_PATH，只能靠它识别输出目录
            add(MediaStore.Video.Media.DATA)
        }.toTypedArray()

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val modifiedIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val relativePathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                val dataIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

                var skippedOwnOutput = 0

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val displayName = cursor.getString(nameIndex) ?: "unknown_$id.mp4"
                    val relativePath = if (relativePathIndex >= 0) {
                        cursor.getString(relativePathIndex)
                    } else {
                        null
                    }
                    val dataPath = if (dataIndex >= 0) cursor.getString(dataIndex) else null

                    // 排除本应用自己的压缩产物，避免反复重编码
                    if (isOwnOutput(displayName, relativePath, dataPath)) {
                        skippedOwnOutput++
                        continue
                    }

                    val uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    result += ScannedVideo(
                        uri = uri,
                        uriString = uri.toString(),
                        displayName = displayName,
                        size = cursor.getLongOrNull(sizeIndex) ?: 0L,
                        dateAdded = cursor.getLongOrNull(addedIndex) ?: 0L,
                        dateModified = cursor.getLongOrNull(modifiedIndex) ?: 0L,
                        durationMs = cursor.getLongOrNull(durationIndex) ?: 0L,
                        width = cursor.getInt(widthIndex),
                        height = cursor.getInt(heightIndex),
                        mimeType = cursor.getString(mimeIndex),
                        relativePath = relativePath
                    )
                }

                if (skippedOwnOutput > 0) {
                    AppLog.i(
                        "SCAN_SKIP_OWN_OUTPUT",
                        "已排除本应用输出的视频 $skippedOwnOutput 个"
                    )
                }
            }
        } catch (e: SecurityException) {
            AppLog.e("SCAN_PERMISSION_DENIED", "扫描被拒绝：${e.message}")
            throw e
        } catch (e: Exception) {
            AppLog.e("SCAN_FAILED", "扫描异常：${e.javaClass.simpleName} ${e.message}")
        }

        AppLog.i(
            "FOUND_VIDEO_COUNT",
            "扫描完成 count=${result.size} cost=${System.currentTimeMillis() - startTime}ms"
        )
        return result
    }
}
