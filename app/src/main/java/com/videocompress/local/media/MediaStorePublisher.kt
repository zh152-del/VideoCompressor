package com.videocompress.local.media

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.videocompress.local.util.AppLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 删除原视频的结果 */
sealed interface DeleteResult {
    data object Ok : DeleteResult
    /** 系统要求用户确认（Android 10+ 对非本应用创建的媒体有此限制） */
    data class NeedConsent(val sender: IntentSender, val message: String?) : DeleteResult
    data class Failed(val message: String) : DeleteResult
}

/**
 * 输出文件发布 + 原视频删除。
 *
 * 发布流程（绝不覆盖用户已有文件）：
 *   temp.mp4 ──► MediaStore 插入（IS_PENDING=1）──► 写入数据 ──► IS_PENDING=0
 *
 * 删除流程：
 *   只有「最终文件已发布且二次验证通过」之后才会被调用，
 *   并且必须捕获 RecoverableSecurityException —— 系统要求用户确认时不能偷偷重试。
 */
object MediaStorePublisher {

    const val OUTPUT_FOLDER = "VideoCompressor"
    private const val RELATIVE_PATH = "Movies/$OUTPUT_FOLDER"
    private const val OUTPUT_MIME = "video/mp4"

    /** 把原始文件名转成输出文件名：VID_x.mp4 → VID_x_compressed.mp4 */
    fun outputNameFor(originalName: String): String {
        val base = originalName.substringBeforeLast('.').ifBlank { "video" }
        return "${sanitize(base)}_compressed.mp4"
    }

    /** 去掉文件名中不适合作为文件名的字符，并限制长度 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .trim()
            .ifEmpty { "video" }
            .take(180)

    /**
     * 发布临时文件到系统相册。
     * @return 成功返回 MediaStore Uri，失败返回 null
     */
    fun publish(context: Context, temp: File, originalName: String): Uri? {
        if (!temp.exists() || temp.length() <= 0) {
            AppLog.e("PUBLISH_FAILED", "临时文件无效：${temp.name}")
            return null
        }

        val displayName = resolveUniqueName(context, outputNameFor(originalName))
        AppLog.i("PUBLISH_START", "发布输出文件 name=$displayName size=${temp.length() / 1024}KB")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishApi29Plus(context, temp, displayName)
        } else {
            publishLegacy(context, temp, displayName)
        }
    }

    // ------------------------------------------------------------------ API 29+
    private fun publishApi29Plus(context: Context, temp: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, OUTPUT_MIME)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }

        val uri = try {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            AppLog.e("PUBLISH_INSERT_FAILED", "MediaStore 插入失败：${e.message}")
            null
        }
        if (uri == null) {
            AppLog.e("PUBLISH_FAILED", "MediaStore 返回空 Uri")
            return null
        }

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                temp.inputStream().use { it.copyTo(output) }
            } ?: run {
                AppLog.e("PUBLISH_FAILED", "无法打开输出流")
                resolver.delete(uri, null, null)
                return null
            }

            val done = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                put(MediaStore.Video.Media.SIZE, temp.length())
            }
            resolver.update(uri, done, null, null)
            AppLog.i("PUBLISH_SUCCESS", "发布成功 uri=$uri")
            uri
        } catch (e: Exception) {
            AppLog.e("PUBLISH_WRITE_FAILED", "写入失败：${e.javaClass.simpleName} ${e.message}")
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    // ------------------------------------------------------------------ API 26~28
    @Suppress("DEPRECATION")
    private fun publishLegacy(context: Context, temp: File, displayName: String): Uri? {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                OUTPUT_FOLDER
            )
            if (!dir.exists() && !dir.mkdirs()) {
                AppLog.e("PUBLISH_FAILED", "无法创建输出目录：${dir.absolutePath}")
                return null
            }
            val target = File(dir, displayName)
            temp.copyTo(target, overwrite = false)

            // 扫描进 MediaStore 后拿 Uri
            var scanned: Uri? = null
            val latch = CountDownLatch(1)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(OUTPUT_MIME)
            ) { _, uri -> scanned = uri; latch.countDown() }
            runCatching { latch.await(10, TimeUnit.SECONDS) }

            val uri = scanned ?: queryUriByPath(context, target.absolutePath)
            if (uri == null) {
                AppLog.e("PUBLISH_FAILED", "扫描后仍拿不到 Uri：${target.absolutePath}")
            } else {
                AppLog.i("PUBLISH_SUCCESS", "发布成功（兼容模式）uri=$uri")
            }
            uri
        } catch (e: Exception) {
            AppLog.e("PUBLISH_FAILED", "兼容模式发布失败：${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun queryUriByPath(context: Context, path: String): Uri? {
        return try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DATA} = ?",
                arrayOf(path),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    android.content.ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析出不冲突的文件名：
     *   x_compressed.mp4 已存在 → x_compressed_1.mp4 → x_compressed_2.mp4
     * 绝不覆盖用户已有视频。
     */
    private fun resolveUniqueName(context: Context, preferred: String): String {
        val existing = existingNames(context).toHashSet()
        if (preferred !in existing) return preferred

        val base = preferred.substringBeforeLast('.')
        val ext = preferred.substringAfterLast('.', "mp4")
        for (i in 1 until 1000) {
            val candidate = "${base}_$i.$ext"
            if (candidate !in existing) return candidate
        }
        return "${base}_${System.currentTimeMillis()}.$ext"
    }

    /** 列出输出目录里已有的文件名 */
    private fun existingNames(context: Context): List<String> {
        val names = mutableListOf<String>()
        try {
            val selection: String?
            val args: Array<String>?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
                args = arrayOf("$RELATIVE_PATH/")
            } else {
                selection = null
                args = null
            }
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                selection,
                args,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    names += cursor.getString(index) ?: continue
                }
            }
        } catch (e: Exception) {
            AppLog.w("NAME_QUERY_FAILED", "查询已有文件名失败：${e.message}")
        }
        return names
    }

    // ------------------------------------------------------------------ 删除

    /**
     * 删除一个媒体文件。
     *
     * Android 10+ 对「不是本应用创建」的媒体要求用户确认，
     * 这时会抛 RecoverableSecurityException，我们把系统的确认流程交给 UI 层，
     * 绝不反复重试，也绝不因此把任务标记成失败。
     */
    fun deleteVideo(context: Context, uri: Uri): DeleteResult {
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) {
                AppLog.i("DELETE_ORIGINAL_SUCCESS", "删除原视频成功 uri=$uri")
                DeleteResult.Ok
            } else {
                AppLog.w("DELETE_ORIGINAL_FAILED", "删除返回 0 行 uri=$uri")
                DeleteResult.Failed("系统未删除任何文件")
            }
        } catch (e: RecoverableSecurityException) {
            AppLog.w("DELETE_NEED_CONSENT", "删除需要用户确认 uri=$uri")
            // RemoteAction.actionIntent 才是系统确认弹窗的 PendingIntent。
            // 部分厂商 ROM 上 userAction / actionIntent 可能为 null，
            // 早期版本直接 .intentSender 会抛 NPE，于是这个「压缩成功、只差用户点一下确认」的
            // 场景被当成未预期异常，任务状态被写成 FAILED，用户看了会以为压缩失败。
            val sender = runCatching { e.userAction?.actionIntent?.intentSender }.getOrNull()
            if (sender != null) {
                DeleteResult.NeedConsent(sender, e.message)
            } else {
                DeleteResult.Failed("系统要求确认后才能删除，但未提供确认入口，请在相册中手动删除")
            }
        } catch (e: SecurityException) {
            AppLog.e("DELETE_SECURITY", "删除权限不足 uri=$uri : ${e.message}")
            DeleteResult.Failed("没有删除权限，需要在系统弹窗中确认")
        } catch (e: Exception) {
            AppLog.e("DELETE_FAILED", "删除异常 uri=$uri : ${e.javaClass.simpleName} ${e.message}")
            DeleteResult.Failed(e.message ?: "删除时发生异常")
        }
    }

    /** 批量删除请求（Android 11+）：返回系统确认弹窗的 IntentSender */
    fun createBatchDeleteRequest(context: Context, uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        }.onFailure {
            AppLog.e("BATCH_DELETE_REQ_FAILED", "创建批量删除请求失败：${it.message}")
        }.getOrNull()
    }

    /** 判断 Uri 是否还能访问（原视频是否还在） */
    fun isUriAlive(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize >= 0 }
                ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** 读取 Uri 对应的真实大小，读不到返回 -1 */
    fun sizeOf(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }
}
