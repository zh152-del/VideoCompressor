package com.videocompress.local.media

import android.content.Context
import android.net.Uri
import com.videocompress.local.util.AppLog
import kotlin.math.abs
import java.io.File

/** 源视频的预期特征，用于校验输出是否「安全」 */
data class SourceExpectation(
    val size: Long,
    val durationMs: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val hasAudio: Boolean
)

/** 验证结果 */
sealed interface VerifyResult {
    /** 验证通过，可以安全替换原视频 */
    data class Ok(
        val size: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val durationMs: Long,
        val hasAudio: Boolean
    ) : VerifyResult

    /** 验证不通过：必须保留原视频，压缩结果作废 */
    data class Invalid(val code: String, val reason: String) : VerifyResult

    /** 压缩本身成功，但体积没有明显下降：保留原视频，丢弃压缩结果 */
    data class NoGain(val reason: String) : VerifyResult
}

/**
 * 输出文件验证器 —— 整个 App 最重要的一道闸门。
 *
 * 原则：宁可多占空间，也绝不误删用户原视频。
 * 任何一项校验不通过，压缩结果直接作废，原视频保持不动。
 *
 * 校验顺序＝ 由「是否为有效视频文件」到「是否值得替换」：
 *   1. 文件存在且非空
 *   2. 能被 Android 媒体框架解析
 *   3. 存在视频轨、时长合理
 *   4. 画面方向、比例、分辨率、音轨与源一致
 *   5. 体积确实变小
 *
 * 注意：源文件与输出文件的元数据都用 VideoProbe 读取。
 * 两边必须走同一套探测逻辑，否则一旦某台机器的尺寸读取行为有偏差，
 * 源和输出会各自朝同一个方向错，比例反而「对得上」，错误就被放过去了。
 */
object OutputVerifier {

    /** 验证临时文件（压缩刚结束时） */
    fun verifyFile(
        context: Context,
        file: File,
        source: SourceExpectation,
        minSavingsPercent: Int
    ): VerifyResult {
        if (!file.exists()) {
            return VerifyResult.Invalid("VERIFY_NOT_FOUND", "输出文件不存在")
        }
        val size = file.length()
        val probe = VideoProbe.probe(context, Uri.fromFile(file))
            ?: return VerifyResult.Invalid("VERIFY_UNREADABLE", "输出文件无法解析")
        return verifyInternal(probe, size, source, minSavingsPercent)
    }

    /** 验证已发布到 MediaStore 的最终文件 */
    fun verifyUri(
        context: Context,
        uri: Uri,
        source: SourceExpectation,
        minSavingsPercent: Int
    ): VerifyResult {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use {
            it.statSize
        }
        if (size == null || size <= 0) {
            return VerifyResult.Invalid("VERIFY_URI_EMPTY", "最终文件为空或不可访问")
        }
        val probe = VideoProbe.probe(context, uri)
            ?: return VerifyResult.Invalid("VERIFY_URI_UNREADABLE", "最终文件无法解析")
        return verifyInternal(probe, size, source, minSavingsPercent)
    }

    private fun verifyInternal(
        probe: ProbeResult,
        size: Long,
        source: SourceExpectation,
        minSavingsPercent: Int
    ): VerifyResult {
        // -------------------------------------------------- 1. 基础体积
        if (size <= 0) {
            return VerifyResult.Invalid("VERIFY_EMPTY", "输出文件大小为 0")
        }
        if (size < 4096) {
            return VerifyResult.Invalid("VERIFY_TOO_SMALL", "输出文件异常小（${size}B）")
        }

        // -------------------------------------------------- 2. 能被解析
        if (!probe.hasVideo) {
            return VerifyResult.Invalid("VERIFY_NO_VIDEO_TRACK", "输出文件没有视频轨")
        }
        if (probe.durationMs <= 0) {
            return VerifyResult.Invalid("VERIFY_ZERO_DURATION", "输出文件时长为 0")
        }
        if (probe.width <= 0 || probe.height <= 0) {
            return VerifyResult.Invalid("VERIFY_BAD_RESOLUTION", "输出文件分辨率无效")
        }

        // 显示尺寸（已按 rotation 换算），后面的方向 / 比例 / 放大检查都基于它
        val outWidth = probe.displayWidth
        val outHeight = probe.displayHeight

        // -------------------------------------------------- 3. 时长一致性
        // 编码器的容器时长与源可能有毫秒级差异，允许 1.5s 或 5% 的容差
        if (source.durationMs > 0) {
            val tolerance = maxOf(1500L, (source.durationMs * 0.05).toLong())
            if (abs(probe.durationMs - source.durationMs) > tolerance) {
                return VerifyResult.Invalid(
                    "VERIFY_DURATION_MISMATCH",
                    "输出时长 ${probe.durationMs}ms 与源 ${source.durationMs}ms 差异过大"
                )
            }
        }

        // -------------------------------------------------- 4. 方向 / 比例 / 放大 / 音轨
        if (source.displayWidth > 0 && source.displayHeight > 0) {
            val srcPortrait = source.displayHeight > source.displayWidth
            val outPortrait = outHeight > outWidth
            if (srcPortrait != outPortrait) {
                return VerifyResult.Invalid(
                    "VERIFY_ORIENTATION_MISMATCH",
                    "输出画面方向与源不一致（源 ${source.displayWidth}×${source.displayHeight}，" +
                        "输出 ${outWidth}×${outHeight}）"
                )
            }

            val srcAspect = source.displayWidth.toFloat() / source.displayHeight
            val outAspect = outWidth.toFloat() / outHeight
            if (abs(outAspect - srcAspect) / srcAspect > 0.08f) {
                return VerifyResult.Invalid(
                    "VERIFY_ASPECT_MISMATCH",
                    "输出画面比例与源差异过大（源 $srcAspect，输出 $outAspect）"
                )
            }

            // 只降不升：输出不允许被放大。
            // 留 2% 容差，因为编码器会做 16 像素对齐，尺寸可能比目标略大一点点。
            val srcPixels = source.displayWidth.toLong() * source.displayHeight
            val outPixels = outWidth.toLong() * outHeight
            if (outPixels > (srcPixels * 1.02f).toLong()) {
                return VerifyResult.Invalid(
                    "VERIFY_UPSCALED",
                    "输出分辨率反而变大了（源 ${source.displayWidth}×${source.displayHeight}，" +
                        "输出 ${outWidth}×${outHeight}）"
                )
            }
        }

        // 源有音轨，输出却没声 —— 这是最不能接受的问题，直接判失败
        if (source.hasAudio && !probe.hasAudio) {
            return VerifyResult.Invalid("VERIFY_AUDIO_LOST", "输出视频丢失了音轨")
        }

        // -------------------------------------------------- 5. 体积收益
        val minAllowed = (source.size * (100 - minSavingsPercent) / 100f).toLong()
        if (minSavingsPercent > 0 && size > minAllowed) {
            return VerifyResult.NoGain(
                "压缩后没有变小（原 ${source.size / 1024}KB，输出 ${size / 1024}KB）"
            )
        }

        AppLog.i(
            "VERIFY_SUCCESS",
            "验证通过 size=${size / 1024}KB ${outWidth}×${outHeight} rot=${probe.rotation} " +
                "duration=${probe.durationMs}ms audio=${probe.hasAudio}"
        )

        return VerifyResult.Ok(
            size = size,
            width = probe.width,
            height = probe.height,
            rotation = probe.rotation,
            durationMs = probe.durationMs,
            hasAudio = probe.hasAudio
        )
    }
}
