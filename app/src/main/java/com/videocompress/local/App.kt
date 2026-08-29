package com.videocompress.local

import android.app.Application
import com.videocompress.local.data.TaskRepository
import com.videocompress.local.media.clearTempFiles
import com.videocompress.local.service.CompressionController
import com.videocompress.local.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 入口。
 *
 * 冷启动时只做两件安全的事：
 *  1. 把上次残留的「处理中」任务恢复成「已中断」，等待用户确认后继续
 *  2. 清理残留临时文件
 *
 * 这里不会自动启动压缩服务 —— 压缩必须由用户在 App 前台点击「开始压缩」触发。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("APP_START", "App 启动")

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // 这两件事都必须在「压缩服务没有在跑」的前提下做，否则会互相打架：
            //  - recoverInterruptedTasks(): 会把「正在编码」的任务误当成上次崩溃的残留，
            //    直接重置成已中断，进度归零不说，还可能让同一个视频被压两遍。
            //  - clearTempFiles(): 会删掉服务此刻正在写入的临时文件，导致编码直接失败。
            // 注意判断要放在协程内部而不是 onCreate 里：
            // 用户可能在 Application 启动后、协程真正执行前就点了「开始压缩」。
            if (CompressionController.running.value) {
                AppLog.i("APP_START_SKIP_RECOVERY", "压缩服务正在运行，跳过启动恢复")
                return@launch
            }
            runCatching { TaskRepository(this@App).recoverInterruptedTasks() }
            runCatching { clearTempFiles(this@App) }
        }
    }
}
