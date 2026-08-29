package com.videocompress.local.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 扫描去重用的轻量指纹，避免把整张表读进内存 */
data class TaskFingerprint(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "originalUri") val uri: String,
    @ColumnInfo(name = "originalSize") val size: Long,
    @ColumnInfo(name = "originalDateModified") val modified: Long,
    @ColumnInfo(name = "status") val status: TaskStatus
)

/** 排序用的轻量行 */
data class OrderRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "dateAdded") val dateAdded: Long,
    @ColumnInfo(name = "durationMs") val durationMs: Long
)

@Dao
interface TaskDao {

    // ------------------------------------------------------------ 观察

    @Query("SELECT * FROM video_tasks ORDER BY queueOrder ASC, id ASC")
    fun observeAll(): Flow<List<VideoTask>>

    /** 首页「当前批次」：取最前面的 2 个待处理任务 */
    @Query(
        "SELECT * FROM video_tasks " +
            "WHERE status IN ('PENDING','INTERRUPTED') " +
            "ORDER BY queueOrder ASC, id ASC LIMIT :limit"
    )
    fun observeWaiting(limit: Int): Flow<List<VideoTask>>

    @Query("SELECT COUNT(*) FROM video_tasks")
    fun observeTotalCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM video_tasks " +
            "WHERE status IN ('PENDING','INTERRUPTED')"
    )
    fun observeWaitingCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM video_tasks " +
            "WHERE status IN ('COMPLETED','COMPLETED_NO_GAIN','COMPLETED_BUT_ORIGINAL_DELETE_FAILED')"
    )
    fun observeDoneCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM video_tasks WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    /** 累计节省的字节数（只统计真正替换成功的） */
    @Query(
        "SELECT COALESCE(SUM(originalSize - outputSize), 0) FROM video_tasks " +
            "WHERE status IN ('COMPLETED','COMPLETED_BUT_ORIGINAL_DELETE_FAILED')"
    )
    fun observeSavedBytes(): Flow<Long>

    /** 等待删除原视频的任务数（需要用户在系统弹窗里确认） */
    @Query("SELECT COUNT(*) FROM video_tasks WHERE status = 'COMPLETED_BUT_ORIGINAL_DELETE_FAILED'")
    fun observePendingDeleteCount(): Flow<Int>

    // ------------------------------------------------------------ 查询

    @Query("SELECT * FROM video_tasks WHERE id = :id")
    suspend fun getById(id: Long): VideoTask?

    @Query("SELECT * FROM video_tasks WHERE originalUri = :uri")
    suspend fun getByUri(uri: String): VideoTask?

    @Query("SELECT id, originalUri, originalSize, originalDateModified, status FROM video_tasks")
    suspend fun getFingerprints(): List<TaskFingerprint>

    /** 元数据发生变化时刷新（只刷新尚未处理的任务，已完成的任务保留历史快照） */
    @Query(
        "UPDATE video_tasks SET originalName = :name, originalSize = :size, " +
            "originalDateModified = :modified, originalDateAdded = :added, " +
            "durationMs = :duration, width = :width, height = :height, " +
            "originalRelativePath = :relativePath " +
            "WHERE id = :id AND status IN ('PENDING','INTERRUPTED')"
    )
    suspend fun refreshMetadata(
        id: Long,
        name: String,
        size: Long,
        modified: Long,
        added: Long,
        duration: Long,
        width: Int,
        height: Int,
        relativePath: String?
    )

    /** 重排队列时读取的轻量行 */
    @Query(
        "SELECT id, originalSize AS size, originalDateAdded AS dateAdded, durationMs " +
            "FROM video_tasks WHERE status IN ('PENDING','INTERRUPTED')"
    )
    suspend fun getOrderingRows(): List<OrderRow>

    @Query(
        "SELECT * FROM video_tasks " +
            "WHERE status IN ('PENDING','INTERRUPTED') " +
            "ORDER BY queueOrder ASC, id ASC LIMIT :limit"
    )
    suspend fun takeWaiting(limit: Int): List<VideoTask>

    @Query("SELECT MAX(queueOrder) FROM video_tasks")
    suspend fun maxQueueOrder(): Long?

    // ------------------------------------------------------------ 写入

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(task: VideoTask): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tasks: List<VideoTask>): List<Long>

    @Update
    suspend fun update(task: VideoTask)

    @Query("UPDATE video_tasks SET queueOrder = :order WHERE id = :id")
    suspend fun updateQueueOrder(id: Long, order: Long)

    /**
     * 按列表顺序批量写回队列序号（列表第 i 项的 queueOrder = i）。
     *
     * 必须用 @Transaction 包起来：以前是在循环里逐条调 updateQueueOrder，
     * 每写一条都要开一次事务并写一次 WAL，几千个任务时重排一次要好几秒，
     * 而重排在扫描后、改排序方式时都会触发。
     */
    @androidx.room.Transaction
    suspend fun updateQueueOrderBatch(ids: List<Long>) {
        ids.forEachIndexed { index, id -> updateQueueOrder(id, index.toLong()) }
    }

    @Query("UPDATE video_tasks SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Int)

    @Query(
        "UPDATE video_tasks SET status = :status, progress = :progress, " +
            "errorMessage = :message, errorCode = :code, " +
            "startedAt = :startedAt, finishedAt = :finishedAt, retryCount = :retryCount " +
            "WHERE id = :id"
    )
    suspend fun updateStatus(
        id: Long,
        status: String,
        progress: Int,
        message: String?,
        code: Int?,
        startedAt: Long?,
        finishedAt: Long?,
        retryCount: Int
    )

    @Query(
        "UPDATE video_tasks SET outputUri = :outputUri, outputName = :outputName, " +
            "outputSize = :outputSize WHERE id = :id"
    )
    suspend fun updateOutput(id: Long, outputUri: String?, outputName: String?, outputSize: Long)

    /** App 被杀 / 手机重启后：把「处理中」恢复成「已中断」，等待用户确认后继续 */
    @Query(
        "UPDATE video_tasks SET status = 'INTERRUPTED', progress = 0 " +
            "WHERE status IN ('PROCESSING','VERIFYING')"
    )
    suspend fun resetBusyToInterrupted()

    /** 把失败 / 取消 / 跳过的任务重新放回队列 */
    @Query(
        "UPDATE video_tasks SET status = 'PENDING', progress = 0, " +
            "errorMessage = NULL, errorCode = NULL " +
            "WHERE status IN ('FAILED','CANCELLED')"
    )
    suspend fun retryAllFailed()

    @Query(
        "UPDATE video_tasks SET status = 'PENDING', progress = 0, " +
            "errorMessage = NULL, errorCode = NULL WHERE id = :id"
    )
    suspend fun retryOne(id: Long)

    /** 手机重启后把「已中断」自动放回队列（状态保存完整，不产生重复压缩） */
    @Query("UPDATE video_tasks SET status = 'PENDING', progress = 0 WHERE status = 'INTERRUPTED'")
    suspend fun requeueInterrupted()

    @Query("SELECT * FROM video_tasks WHERE status = 'COMPLETED_BUT_ORIGINAL_DELETE_FAILED'")
    suspend fun getPendingDeleteTasks(): List<VideoTask>
}
