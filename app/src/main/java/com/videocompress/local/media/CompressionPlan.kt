package com.videocompress.local.media

import com.videocompress.local.data.AppSettings
import com.videocompress.local.data.QualityPreset
import com.videocompress.local.data.ResolutionOption
import com.videocompress.local.util.AppLog
import kotlin.math.roundToInt

/**
 * 一次压缩的具体参数。
 *
 * 关于 [scaleWidth] / [scaleHeight] 为什么叫「编码帧尺寸」而不是「显示尺寸」：
 *  Media3 的 Presentation 作用于**解码后的原始帧**（即 MediaFormat 里的 width/height，
 *  未经旋转），而 Transformer 默认会保留源视频的 rotation 元数据。
 *  因此对于 rotation=90/270 的竖屏视频，显示宽高与编码帧宽高是**互换**的。
 *  早期版本直接把「显示高度」传给 Presentation.createForHeight()，
 *  导致手机拍摄的竖屏视频实际输出尺寸远大于目标（例如目标 720P 却输出 1280 高），
 *  压缩收益远低于预期。现在统一换算成编码帧尺寸，保证横屏竖屏都精确。
 */
data class CompressionPlan(
    /** 输出编码帧宽度（偶数）；0 表示不引入缩放 Effect，保持原分辨率 */
    val scaleWidth: Int,
    /** 输出编码帧高度（偶数）；0 表示不引入缩放 Effect */
    val scaleHeight: Int,
    val videoBitrate: Int,
    val audioBitrate: Int,
    /** 预估输出体积，用于存储空间预检 */
    val estimatedOutputBytes: Long
)

/**
 * 根据质量档位、分辨率策略与源视频信息计算编码参数。
 *
 * 目标：
 *  1. 只降不升 —— 分辨率永远不会被放大
 *  2. 有收益 —— 预估输出体积必须明显小于原文件，否则建议跳过
 *  3. 不追求极限 —— 码率有下限，宁可跳过也不把视频压成一坨马赛克
 */
object CompressionPlanFactory {

    private const val MIN_VIDEO_BITRATE = 400_000        // 400 kbps
    private const val MAX_VIDEO_BITRATE = 80_000_000     // 80 Mbps

    fun create(
        probe: ProbeResult,
        originalSize: Long,
        settings: AppSettings
    ): CompressionPlan? {
        val displayWidth = probe.displayWidth
        val displayHeight = probe.displayHeight
        if (displayWidth <= 0 || displayHeight <= 0) {
            AppLog.w("PLAN_SKIP", "无法解析分辨率，跳过")
            return null
        }

        // ---------------------------------------------------------- 分辨率
        // 只下调：目标高度大于原始高度时保持原样
        val targetDisplayHeight = if (settings.resolution.targetHeight > 0) {
            minOf(displayHeight, settings.resolution.targetHeight)
        } else {
            displayHeight
        }
        // 缩放幅度太小时不值得引入缩放 Effect：
        // 每加一层 Presentation 都要走一遍 GL 管线，既有额外耗时也会有轻微重采样损耗，
        // 而分辨率几乎没变时收益为零。因此只有真正降了 2% 以上才缩放。
        val scaled = targetDisplayHeight < displayHeight * 0.98f

        // 目标「显示尺寸」：用户看到的画面尺寸，必须偶数（H.264 4:2:0 要求）
        val outDisplayWidth = roundToEven(
            displayWidth * (targetDisplayHeight.toFloat() / displayHeight)
        )
        val outDisplayHeight = roundToEven(targetDisplayHeight.toFloat())

        // 换算成「编码帧尺寸」：rotation=90/270 时宽高互换
        val rotated = probe.rotation == 90 || probe.rotation == 270
        val scaleWidth = if (scaled) {
            if (rotated) outDisplayHeight else outDisplayWidth
        } else {
            0
        }
        val scaleHeight = if (scaled) {
            if (rotated) outDisplayWidth else outDisplayHeight
        } else {
            0
        }

        // ---------------------------------------------------------- 码率
        // 用显示方向的像素数估算，与旋转无关
        val pixelCount = outDisplayWidth.toLong() * outDisplayHeight.toLong()
        val fpsFactor = (probe.frameRate / 30f).coerceIn(0.5f, 2.0f)
        var videoBitrate = (pixelCount * settings.quality.bitsPerPixel * fpsFactor)
            .roundToInt()
            .coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)

        val audioBitrate = if (probe.hasAudio) settings.quality.audioBitrate else 0

        // ---------------------------------------------------------- 收益约束
        // 用「目标收益」反推一个体积预算，保证压缩后确实能变小
        val durationSec = (probe.durationMs / 1000f).coerceAtLeast(1f)
        val budgetBytes = (originalSize * (100 - settings.minSavingsPercent) / 100f).toLong()
            .coerceAtLeast(0L)

        var estimated = ((videoBitrate + audioBitrate).toLong() * durationSec / 8f).toLong() +
            // 容器开销，长视频尤其明显
            (durationSec * 8_000).toLong()

        if (budgetBytes > 0 && estimated > budgetBytes) {
            val allowedTotalBps = ((budgetBytes - durationSec * 8_000) * 8f / durationSec)
                .toLong()
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt() - audioBitrate
            videoBitrate = allowedTotalBps.coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)
            estimated = ((videoBitrate + audioBitrate).toLong() * durationSec / 8f).toLong() +
                (durationSec * 8_000).toLong()

            if (estimated > budgetBytes) {
                // 即使压到最低码率也达不到目标收益 —— 直接跳过，保留原视频
                AppLog.i(
                    "PLAN_NO_BENEFIT",
                    "按当前设置无法达到 ${settings.minSavingsPercent}% 收益，跳过 " +
                        "original=$originalSize estimated=$estimated"
                )
                return null
            }
        }

        AppLog.i(
            "PLAN_READY",
            "quality=${settings.quality.name} rot=${probe.rotation} " +
                "srcDisplay=${displayWidth}x${displayHeight} " +
                "outDisplay=${outDisplayWidth}x${outDisplayHeight} " +
                "scaleFrame=${scaleWidth}x$scaleHeight scaled=$scaled " +
                "videoBitrate=$videoBitrate audioBitrate=$audioBitrate " +
                "estimate=${estimated / 1024}KB"
        )

        return CompressionPlan(
            scaleWidth = scaleWidth,
            scaleHeight = scaleHeight,
            videoBitrate = videoBitrate,
            audioBitrate = audioBitrate,
            estimatedOutputBytes = estimated
        )
    }
}

/** H.264 4:2:0 要求宽高为偶数 */
private fun roundToEven(value: Float): Int {
    val rounded = value.roundToInt().coerceAtLeast(2)
    return if (rounded % 2 == 0) rounded else rounded + 1
}

/** 探测结果的显示宽度（已考虑旋转） */
val ProbeResult.displayWidth: Int
    get() = if (rotation == 90 || rotation == 270) height else width

/** 探测结果的显示高度（已考虑旋转） */
val ProbeResult.displayHeight: Int
    get() = if (rotation == 90 || rotation == 270) width else height
