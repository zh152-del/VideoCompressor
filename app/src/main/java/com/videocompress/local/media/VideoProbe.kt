package com.videocompress.local.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.videocompress.local.util.AppLog
import kotlin.math.roundToInt

/** 视频探测结果 */
data class ProbeResult(
    val durationMs: Long,
    /** 编码宽度（未考虑旋转） */
    val width: Int,
    /** 编码高度（未考虑旋转） */
    val height: Int,
    /** 旋转元数据 0/90/180/270（已归一化） */
    val rotation: Int,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val videoMime: String?,
    val isHdr: Boolean,
    val frameRate: Float
)

/** 确认可以安全重新编码的输入编码格式 */
private val SUPPORTED_INPUT_MIMES = setOf(
    MediaFormat.MIMETYPE_VIDEO_AVC,      // H.264
    MediaFormat.MIMETYPE_VIDEO_HEVC,     // H.265
    MediaFormat.MIMETYPE_VIDEO_MPEG4,    // MPEG-4 SP/ASP
    MediaFormat.MIMETYPE_VIDEO_H263,     // H.263
    MediaFormat.MIMETYPE_VIDEO_VP8,      // VP8
    MediaFormat.MIMETYPE_VIDEO_VP9,      // VP9
    MediaFormat.MIMETYPE_VIDEO_AV1       // AV1
)

/**
 * 视频元数据探测。
 *
 * 目的：在真正开始编码之前就判断这个视频能不能被「安全」处理。
 * 一旦判断不安全（HDR / 10bit / 无视频轨 / 编码不支持），直接跳过并保留原视频，
 * 绝不硬着头皮编码然后删除原文件。
 *
 * 尺寸来源优先用 MediaExtractor 而不是 MediaMetadataRetriever：
 * MediaMetadataRetriever 的 METADATA_KEY_VIDEO_WIDTH/HEIGHT 在部分厂商 ROM（OriginOS 等）
 * 上返回的是**已经算过旋转的显示尺寸**，再拿它叠加 rotation 就会把横竖屏判反，
 * 进而算出完全错误的缩放目标。MediaFormat 的 KEY_WIDTH/KEY_HEIGHT 语义明确是编码尺寸。
 */
object VideoProbe {

    fun probe(context: Context, uri: Uri): ProbeResult? {
        probeWithExtractor(context, uri)?.let { return it }
        return probeWithRetriever(context, uri)
    }

    /** 输入编码是否在安全处理范围内 */
    fun isSupportedCodec(mime: String?): Boolean =
        mime != null && mime in SUPPORTED_INPUT_MIMES

    // ------------------------------------------------------------------ 主路径

    /**
     * 用 MediaExtractor 读取全部关键元数据：一次遍历就能拿到
     * 编码尺寸 / 旋转 / 时长 / 帧率 / 编码格式 / HDR / 是否有音轨。
     */
    private fun probeWithExtractor(context: Context, uri: Uri): ProbeResult? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)

            var width = 0
            var height = 0
            var rotation = 0
            var durationMs = 0L
            var frameRate = 0f
            var videoMime: String? = null
            var isHdr = false
            var hasAudio = false
            var hasVideo = false

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("video/")) {
                    hasVideo = true
                    if (videoMime == null) {
                        videoMime = mime
                        width = format.getIntSafe(MediaFormat.KEY_WIDTH)
                        height = format.getIntSafe(MediaFormat.KEY_HEIGHT)
                        durationMs = format.getLongSafe(MediaFormat.KEY_DURATION) / 1000L
                        rotation = normalizeRotation(
                            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                                format.getIntSafe(MediaFormat.KEY_ROTATION)
                            } else {
                                0
                            }
                        )
                        val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            format.getIntSafe(MediaFormat.KEY_FRAME_RATE)
                        } else {
                            0
                        }
                        if (fps in 1..240) frameRate = fps.toFloat()
                        isHdr = detectHdr(format)
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                }
            }

            if (!hasVideo || width <= 0 || height <= 0) {
                AppLog.w("PROBE_EXTRACTOR_INCOMPLETE", "MediaExtractor 未读到有效视频轨，转用回退方案")
                null
            } else {
                ProbeResult(
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    rotation = rotation,
                    hasAudio = hasAudio,
                    hasVideo = hasVideo,
                    videoMime = videoMime,
                    isHdr = isHdr,
                    frameRate = if (frameRate > 0f) frameRate else 30f
                )
            }
        } catch (e: Exception) {
            AppLog.w("PROBE_EXTRACTOR_FAILED", "MediaExtractor 探测失败：${e.message}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    // ------------------------------------------------------------------ 回退

    /** 个别容器 MediaExtractor 解析不出尺寸时的兜底方案 */
    private fun probeWithRetriever(context: Context, uri: Uri): ProbeResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0

            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0

            val rotation = normalizeRotation(
                retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0
            )

            val hasAudio = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
            ) == "yes"

            val hasVideo = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
            ) == "yes"

            val containerMime = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_MIMETYPE
            )

            if (!hasVideo || width <= 0 || height <= 0) {
                AppLog.w("PROBE_RETRIEVER_INCOMPLETE", "回退探测仍拿不到有效分辨率")
                null
            } else {
                ProbeResult(
                    durationMs = duration,
                    width = width,
                    height = height,
                    rotation = rotation,
                    hasAudio = hasAudio,
                    hasVideo = hasVideo,
                    videoMime = containerMime,
                    isHdr = false,
                    frameRate = 30f
                )
            }
        } catch (e: Exception) {
            AppLog.e("PROBE_FAILED", "探测失败 uri=$uri : ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 厂商偶尔会给出 360、-90 之类非常规值，统一收敛到 0/90/180/270 */
    private fun normalizeRotation(raw: Int): Int {
        val normalized = ((raw % 360) + 360) % 360
        return when (normalized) {
            in 45 until 135 -> 90
            in 135 until 225 -> 180
            in 225 until 315 -> 270
            else -> 0
        }
    }

    private fun MediaFormat.getIntSafe(key: String): Int =
        runCatching { getInteger(key) }.getOrDefault(0)

    private fun MediaFormat.getLongSafe(key: String): Long =
        runCatching { getLong(key) }.getOrDefault(0L)

    /**
     * HDR / 10bit 判定。
     *
     * 只看容器或扩展名是判断不出来的，必须读 color-transfer 与 profile：
     *  - HLG（3）与 PQ/ST2084（6）是明确的 HDR 传输函数
     *  - HEVC Main10（10bit）也无法安全转成 8bit H.264，可能存在色带/色彩偏移
     */
    private fun detectHdr(format: MediaFormat): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                } else {
                    MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                }
                if (transfer == MediaFormat.COLOR_TRANSFER_HLG ||
                    transfer == MediaFormat.COLOR_TRANSFER_ST2084
                ) {
                    return true
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                    format.getInteger(MediaFormat.KEY_PROFILE)
                } else {
                    0
                }
                if (profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                ) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}

/** 把浮点帧率规整成编码器更容易接受的整数 */
fun normalizeFrameRate(fps: Float): Int = fps.roundToInt().coerceIn(1, 240)
