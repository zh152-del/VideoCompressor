package com.videocompress.local.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Insert
    suspend fun insert(entry: LogEntry): Long

    /** 日志按时间倒序展示，最多 500 条，避免列表过长 */
    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentSync(limit: Int = 2000): List<LogEntry>

    /** 只保留最近的 3000 条，防止数据库无限增长 */
    @Query(
        "DELETE FROM log_entries WHERE id NOT IN " +
            "(SELECT id FROM log_entries ORDER BY id DESC LIMIT 3000)"
    )
    suspend fun trim()

    @Query("DELETE FROM log_entries")
    suspend fun clear()
}
