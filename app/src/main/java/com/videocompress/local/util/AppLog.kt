package com.videocompress.local.util

import android.content.Context
import com.videocompress.local.data.AppDatabase
import com.videocompress.local.data.LogDao
import com.videocompress.local.data.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志系统。
 *
 * 关键节点（扫描、开始压缩、编码完成、验证成功、删除原视频、错误码）全部落库，
 * 同时追加写入文件，方便导出排查。
 *
 * 写日志是异步的，绝不阻塞压缩主流程；日志写入失败也不能影响压缩任务。
 */
object AppLog {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    private val queue = Channel<LogEntry>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var dao: LogDao? = null

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var insertCount = 0

    fun init(context: Context) {
        if (dao != null) return
        synchronized(this) {
            if (dao != null) return
            dao = AppDatabase.getInstance(context).logDao()
            logDir = File(context.filesDir, "logs").apply { mkdirs() }
            scope.launch {
                for (entry in queue) {
                    runCatching {
                        dao?.insert(entry)
                        if (++insertCount % 50 == 0) dao?.trim()
                    }
                    runCatching { appendToFile(entry) }
                }
            }
        }
    }

    /** 常规信息 */
    fun i(code: String, message: String, taskId: Long? = null) =
        push("I", code, message, taskId)

    /** 警告：不影响任务继续 */
    fun w(code: String, message: String, taskId: Long? = null) =
        push("W", code, message, taskId)

    /** 错误：任务失败或关键步骤异常 */
    fun e(code: String, message: String, taskId: Long? = null) =
        push("E", code, message, taskId)

    private fun push(level: String, code: String, message: String, taskId: Long?) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            code = code,
            message = message,
            taskId = taskId
        )
        queue.trySend(entry)
    }

    private fun appendToFile(entry: LogEntry) {
        val dir = logDir ?: return
        val file = File(dir, "compress-${fileDateFormat.format(Date(entry.timestamp))}.log")
        file.appendText(
            "${timeFormat.format(Date(entry.timestamp))} [${entry.level}] ${entry.code} " +
                "task=${entry.taskId ?: "-"} :: ${entry.message}\n"
        )
    }

    /** 导出用：返回最近的日志文件列表（按修改时间倒序） */
    fun logFiles(): List<File> =
        (logDir?.listFiles()?.toList() ?: emptyList())
            .sortedByDescending { it.lastModified() }
}
