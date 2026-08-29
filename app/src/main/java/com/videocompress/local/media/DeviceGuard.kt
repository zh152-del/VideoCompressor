package com.videocompress.local.media

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.videocompress.local.util.AppLog
import java.io.File

/** 电池状态 */
data class BatteryState(val percent: Int, val isCharging: Boolean)

/** 停止新任务的理由（为 null 表示可以继续） */
data class StopReason(val code: String, val message: String)

/**
 * 设备状态守卫：存储 / 电量 / 温度。
 *
 * 压缩是高负载任务，连续跑几十个视频会：
 *  - 把手机烤到降频
 *  - 把电量榨干
 *  - 触发 VIVO 的后台限制
 *
 * 这里在「每批任务之间」做检查，一旦越界就停止开新任务，
 * 但当前正在编码的视频会正常跑完并完整保存状态，不会半途丢文件。
 */
object DeviceGuard {

    /** 需要的额外安全余量：原视频 + 临时文件 + 最终文件会同时存在 */
    private const val SAFETY_MARGIN_BYTES = 512L * 1024 * 1024 // 512MB

    /** 可用空间（字节）。取外部存储目录所在分区 */
    fun availableBytes(context: Context): Long {
        val dirs = listOfNotNull(
            context.getExternalFilesDir(null),
            Environment.getExternalStorageDirectory(),
            context.filesDir
        )
        for (dir in dirs) {
            val available = runCatching {
                val stat = StatFs(dir.absolutePath)
                stat.availableBytes
            }.getOrNull()
            if (available != null && available > 0) return available
        }
        return 0L
    }

    /**
     * 空间预检。
     * 需求空间 = 预估输出体积 × 2（临时文件 + 最终文件）+ 安全余量
     */
    fun checkStorage(context: Context, estimatedOutputBytes: Long): StopReason? {
        val available = availableBytes(context)
        val need = estimatedOutputBytes * 2 + SAFETY_MARGIN_BYTES
        return if (available <= 0 || available < need) {
            val reason = StopReason(
                "LOW_STORAGE",
                "剩余存储空间不足，无法安全生成压缩文件（可用 " +
                    "${available / 1024 / 1024}MB，需要约 ${need / 1024 / 1024}MB）"
            )
            AppLog.w(reason.code, reason.message)
            reason
        } else {
            null
        }
    }

    fun battery(context: Context): BatteryState {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else 50
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            BatteryState(percent, charging)
        } catch (e: Exception) {
            BatteryState(50, true)
        }
    }

    /** 热状态：>= SEVERE(4) 认为过热 */
    fun thermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            pm?.currentThermalStatus ?: -1
        }.getOrDefault(-1)
    }

    /**
     * 是否应该停止开启下一批任务。
     * 只在批与批之间调用，不会打断正在进行的压缩。
     */
    fun shouldStopBeforeNextBatch(
        context: Context,
        settings: com.videocompress.local.data.AppSettings
    ): StopReason? {
        val battery = battery(context)

        if (settings.onlyWhenCharging && !battery.isCharging) {
            return StopReason("NEED_CHARGING", "已设置为仅充电时继续，等待连接充电器")
        }
        if (battery.percent < settings.batteryFloorPercent && !battery.isCharging) {
            return StopReason(
                "LOW_BATTERY",
                "电量低于 ${settings.batteryFloorPercent}%（当前 ${battery.percent}%），已停止新任务"
            )
        }
        if (settings.thermalGuard) {
            val status = thermalStatus(context)
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                return StopReason("THERMAL_HIGH", "手机温度过高，已停止新任务以避免损伤硬件")
            }
        }
        return null
    }
}

/** 清理临时目录（只清理本 App 自己生成的 temp 文件） */
fun clearTempFiles(context: Context) {
    runCatching {
        val dir = File(context.cacheDir, "compress_temp")
        if (dir.exists()) dir.deleteRecursively()
    }
}

/** 获取临时输出目录（app 私有，不污染相册） */
fun tempDir(context: Context): File =
    File(context.cacheDir, "compress_temp").apply { mkdirs() }

/** 为某个任务生成临时文件名 */
fun tempFileFor(context: Context, taskId: Long): File =
    File(tempDir(context), "temp_$taskId.mp4")
