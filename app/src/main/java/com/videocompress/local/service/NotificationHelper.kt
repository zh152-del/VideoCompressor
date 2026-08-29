package com.videocompress.local.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.videocompress.local.MainActivity
import com.videocompress.local.R

/**
 * 压缩通知。
 *
 * 进度更新做了节流：只在「变化超过 2% 且距上次更新超过 800ms」时才真正 notify，
 * 避免每几十毫秒一次 IPC 把系统拖慢、额外耗电。
 */
class NotificationHelper(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var lastNotifyAt = 0L
    private var lastProgress = -1

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "视频压缩",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示视频压缩进度"
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /** 首帧通知：开始启动服务，进度未知 */
    fun buildInitial(): Notification = build(
        title = "正在准备压缩",
        text = "正在读取视频信息…",
        progress = 0,
        indeterminate = true
    )

    /**
     * 更新压缩进度。
     * @param name 当前视频名
     * @param progress 0~100
     * @param index 当前是批次内第几个（从 1 开始）
     * @param total 批次内总数
     */
    fun updateTask(name: String, progress: Int, index: Int, total: Int) {
        val now = System.currentTimeMillis()
        val progressChanged = progress != lastProgress
        val enoughDelta = progress == 0 || progress >= 100 || progress >= lastProgress + 2
        if (!progressChanged || !enoughDelta || now - lastNotifyAt < MIN_INTERVAL_MS) return

        lastProgress = progress
        lastNotifyAt = now

        val text = if (total > 1) "第 $index / $total · $name" else name
        manager.notify(
            NOTIFICATION_ID,
            build(
                title = "正在压缩视频",
                text = text,
                progress = progress,
                indeterminate = false
            )
        )
    }

    /** 因为电量 / 温度 / 存储等原因主动停止 */
    fun updateStopped(message: String) {
        manager.notify(
            NOTIFICATION_ID,
            build(title = "压缩已暂停", text = message, progress = 100, indeterminate = false)
        )
    }

    /** 全部完成或用户取消 */
    fun finish(message: String) {
        manager.notify(
            NOTIFICATION_ID,
            build(title = "视频压缩结束", text = message, progress = 100, indeterminate = false)
        )
    }

    private fun build(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            immutableFlag()
        )

        val cancelIntent = PendingIntent.getService(
            context,
            1,
            CompressionService.cancelIntent(context),
            immutableFlag(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_compress)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // 只提供「取消」：暂停在 Media3 Transformer 上没有可靠实现，不做假按钮
            .addAction(
                R.drawable.ic_stat_compress,
                "取消",
                cancelIntent
            )
            .build()
    }

    private fun immutableFlag(extra: Int = 0): Int =
        extra or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }

    companion object {
        const val CHANNEL_ID = "video_compress_channel"
        const val NOTIFICATION_ID = 20260829
        private const val MIN_INTERVAL_MS = 800L
    }
}
