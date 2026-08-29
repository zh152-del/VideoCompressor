package com.videocompress.local.data

import android.content.Context
import android.net.Uri
import com.videocompress.local.media.MediaStorePublisher
import com.videocompress.local.media.ScannedVideo
import com.videocompress.local.util.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 一次扫描的同步结果 */
data class SyncResult(val added: Int, val updated: Int, val total: Int)

/**
 * 任务仓库：把 MediaStore 的扫描结果同步成任务队列。
 *
 * 核心原则：
 *  - 只同步元数据，绝不读取视频内容
 *  - 已存在的任务不重建，避免重复压缩
 *  - 已完成的任务保留历史快照，元数据变化不会让它重新排队
 */
class TaskRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(context).taskDao()

    fun observeAll(): Flow<List<VideoTask>> = dao.observeAll()

    fun observeWaiting(limit: Int): Flow<List<VideoTask>> = dao.observeWaiting(limit)

    fun observeCounts(): Flow<TaskCounts> = combineCounts()

    suspend fun syncFromMediaStore(
        videos: List<ScannedVideo>,
        settings: AppSettings
    ): SyncResult {
        val existing = dao.getFingerprints().associateBy { it.uri }
        val minBytes = settings.minSizeMb.toLong() * 1024L * 1024L
        val now = System.currentTimeMillis()

        val newTasks = mutableListOf<VideoTask>()
        var updated = 0

        for (video in videos) {
            if (minBytes > 0 && video.size < minBytes) continue

            val known = existing[video.uriString]
            if (known == null) {
                newTasks += VideoTask(
                    originalUri = video.uriString,
                    originalName = video.displayName,
                    originalSize = video.size,
                    originalMimeType = video.mimeType ?: "video/mp4",
                    originalDateModified = video.dateModified,
                    originalDateAdded = video.dateAdded,
                    originalRelativePath = video.relativePath,
                    durationMs = video.durationMs,
                    width = video.width,
                    height = video.height,
                    rotation = 0,
                    hasAudio = true,
                    videoMime = null,
                    isHdr = false,
                    queueOrder = now,
                    status = TaskStatus.PENDING,
                    createdAt = now
                )
            } else if (known.size != video.size || known.modified != video.dateModified) {
                dao.refreshMetadata(
                    id = known.id,
                    name = video.displayName,
                    size = video.size,
                    modified = video.dateModified,
                    added = video.dateAdded,
                    duration = video.durationMs,
                    width = video.width,
                    height = video.height,
                    relativePath = video.relativePath
                )
                updated++
            }
        }

        val inserted = dao.insertAll(newTasks).count { it > 0 }
        AppLog.i(
            "SYNC_DONE",
            "扫描同步完成 total=${videos.size} added=$inserted updated=$updated"
        )

        reorder(settings)
        return SyncResult(inserted, updated, videos.size)
    }

    /** 按用户选择的排序规则重排「等待中」的任务 */
    suspend fun reorder(settings: AppSettings) {
        val rows = dao.getOrderingRows()
        val sorted = when (settings.sortOrder) {
            SortOrder.DATE_DESC -> rows.sortedByDescending { it.dateAdded }
            SortOrder.DATE_ASC -> rows.sortedBy { it.dateAdded }
            SortOrder.SIZE_DESC -> rows.sortedByDescending { it.size }
            SortOrder.DURATION_DESC -> rows.sortedByDescending { it.durationMs }
        }
        // 一次性批量写回，避免在循环里逐条 update（每个任务各开一次事务）
        dao.updateQueueOrderBatch(sorted.map { it.id })
        AppLog.i("REORDER", "队列重排完成 order=${settings.sortOrder.name} count=${rows.size}")
    }

    /** App 冷启动时的恢复：把残留的「处理中」重置为「已中断」 */
    suspend fun recoverInterruptedTasks() {
        val daoRef = dao
        runCatching { daoRef.resetBusyToInterrupted() }
            .onSuccess { AppLog.i("RECOVERY", "启动恢复：残留处理中任务已重置为「已中断」") }
    }

    suspend fun retryAllFailed() = dao.retryAllFailed()
    suspend fun retryOne(id: Long) = dao.retryOne(id)
    suspend fun requeueInterrupted() = dao.requeueInterrupted()
    suspend fun pendingDeleteTasks() = dao.getPendingDeleteTasks()

    /**
     * 用户在系统弹窗里确认删除原视频之后调用：
     * 只把「原视频确实已经不在了」的任务标记成 COMPLETED，
     * 绝不因为一次确认就把所有任务都改成已完成。
     */
    suspend fun refreshPendingDeleteTasks(): Int {
        var finalized = 0
        for (task in dao.getPendingDeleteTasks()) {
            val alive = MediaStorePublisher.isUriAlive(appContext, Uri.parse(task.originalUri))
            if (!alive) {
                dao.updateStatus(
                    id = task.id,
                    status = TaskStatus.COMPLETED.name,
                    progress = 100,
                    message = null,
                    code = null,
                    startedAt = task.startedAt,
                    finishedAt = System.currentTimeMillis(),
                    retryCount = task.retryCount
                )
                finalized++
            }
        }
        if (finalized > 0) AppLog.i("DELETE_CONFIRMED", "确认删除完成 count=$finalized")
        return finalized
    }

    /**
     * 统计信息。
     *
     * 以前这里为了拿 total 直接订阅 observeAll()，
     * 等于把整张任务表（每个任务的全部字段）读进内存再数个数，
     * 只要任务一多就白白吃掉几十 MB 内存，而且每次有任务变动都要重新反序列化整张表。
     * 统计只需要数字，用 observeTotalCount() 走 COUNT(*) 就够了。
     */
    private fun combineCounts(): Flow<TaskCounts> = combine(
        dao.observeTotalCount(),
        dao.observeWaitingCount(),
        dao.observeDoneCount(),
        dao.observeFailedCount(),
        dao.observeSavedBytes()
    ) { total, waiting, done, failed, saved ->
        TaskCounts(
            total = total,
            waiting = waiting,
            done = done,
            failed = failed,
            savedBytes = saved
        )
    }
}

/** 首页统计 */
data class TaskCounts(
    val total: Int = 0,
    val waiting: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val savedBytes: Long = 0
)
