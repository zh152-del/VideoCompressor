package com.videocompress.local.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 日志条目。
 *
 * 所有关键节点（扫描、开始压缩、编码完成、验证成功、删除成功、错误码）
 * 都会落库，方便后续排查问题。
 */
@Entity(
    tableName = "log_entries",
    indices = [Index(value = ["timestamp"])]
)
data class LogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    /** I / W / E */
    val level: String,
    /** SCAN_START / TASK_START / ENCODE_COMPLETE / ERROR_CODE ... */
    val code: String,
    val message: String,
    val taskId: Long? = null
)
