package com.videocompress.local.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个视频的压缩任务。
 *
 * 只保存 Uri 与必要元数据，绝不保存视频二进制数据。
 * 所有状态都必须落库，避免 App 被杀 / 手机重启后出现
 * 「原视频已删除但状态还是 PENDING」这类灾难性状态。
 */
@Entity(
    tableName = "video_tasks",
    indices = [
        Index(value = ["originalUri"], unique = true),
        Index(value = ["status"]),
        Index(value = ["queueOrder"])
    ]
)
data class VideoTask(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ------------------------------------------------------------ 原视频信息
    /** MediaStore Uri 字符串，全局唯一，作为去重主键 */
    val originalUri: String,

    val originalName: String,

    val originalSize: Long,

    val originalMimeType: String,

    val originalDateModified: Long,

    /** 加入系统相册的时间（秒），用于「最新优先 / 最旧优先」排序 */
    val originalDateAdded: Long,

    /** 原视频所在目录（API 29+ 才有值），用于把输出放到同一目录附近 */
    val originalRelativePath: String?,

    val durationMs: Long,

    /** 编码宽度（未考虑旋转） */
    val width: Int,

    /** 编码高度（未考虑旋转） */
    val height: Int,

    /** 旋转元数据：0 / 90 / 180 / 270 */
    val rotation: Int,

    /** 是否包含音轨（没有音轨时不要求输出含音轨） */
    val hasAudio: Boolean,

    /** 探测到的视频编码 MIME，例如 video/hevc */
    val videoMime: String?,

    /** 是否为 HDR / 10bit 等不安全场景 */
    val isHdr: Boolean,

    // ------------------------------------------------------------ 队列与状态
    /** 队列顺序，扫描时按用户选择的排序写入 */
    val queueOrder: Long,

    val status: TaskStatus,

    val progress: Int = 0,

    // ------------------------------------------------------------ 输出信息
    val outputUri: String? = null,

    val outputName: String? = null,

    val outputSize: Long = 0L,

    // ------------------------------------------------------------ 错误与重试
    val errorCode: Int? = null,

    val errorMessage: String? = null,

    val retryCount: Int = 0,

    // ------------------------------------------------------------ 时间戳
    val createdAt: Long,

    val startedAt: Long? = null,

    val finishedAt: Long? = null
) {
    /** 考虑到旋转后的显示宽度 */
    val displayWidth: Int
        get() = if (rotation == 90 || rotation == 270) height else width

    /** 考虑到旋转后的显示高度 */
    val displayHeight: Int
        get() = if (rotation == 90 || rotation == 270) width else height
}
