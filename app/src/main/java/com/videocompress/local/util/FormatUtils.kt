package com.videocompress.local.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 字节数转人类可读字符串（使用 1024 进制，和文件管理器一致） */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) {
        "${bytes}B"
    } else {
        String.format(Locale.CHINA, "%.1f%s", value, units[unit])
    }
}

/** 毫秒转 mm:ss / hh:mm:ss */
fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "--:--"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.CHINA, "%02d:%02d", minutes, seconds)
    }
}

/** 时间戳转 yyyy-MM-dd HH:mm:ss */
fun formatTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))

/** 时间戳转 HH:mm:ss（日志用） */
fun formatClock(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))

/** 分辨率字符串，考虑旋转后按「显示方向」输出 */
fun formatResolution(width: Int, height: Int, rotation: Int): String {
    if (width <= 0 || height <= 0) return "未知"
    val w = if (rotation == 90 || rotation == 270) height else width
    val h = if (rotation == 90 || rotation == 270) width else height
    return "${w}×${h}"
}

/** 压缩收益百分比，例如 42 */
fun savingPercent(original: Long, output: Long): Int {
    if (original <= 0) return 0
    val saved = ((original - output).toDouble() / original * 100).toInt()
    return saved.coerceIn(-999, 100)
}
