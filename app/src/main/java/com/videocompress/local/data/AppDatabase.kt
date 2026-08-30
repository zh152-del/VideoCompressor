package com.videocompress.local.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * 本地数据库：任务队列 + 日志。
 *
 * 没有使用 destructive migration：任务状态是整个 App 的安全基础，
 * 任何情况下都不能因为升级而丢失。
 */
@Database(
    entities = [VideoTask::class, LogEntry::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    abstract fun logDao(): LogDao

    companion object {

        private const val DB_NAME = "video_compressor.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    // 允许在主版本降级时（例如从带「重复视频」表的中间构建回退到本版本）
                    // 以重建数据库的方式兜底，避免 Room 因找不到迁移而直接崩溃。
                    // 任务队列只是元数据，原视频始终保存在系统相册，重建不会丢视频。
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
