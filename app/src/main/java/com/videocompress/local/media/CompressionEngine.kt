package com.videocompress.local.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationException
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.TransformationResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.videocompress.local.util.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** 一次压缩的结果 */
sealed interface CompressOutcome {
    data class Success(val outputSize: Long) : CompressOutcome
    data class Failure(val errorCode: Int, val message: String) : CompressOutcome
    data object Cancelled : CompressOutcome
}

/**
 * Media3 Transformer 压缩引擎。
 *
 * 关键设计：
 *  - Transformer 必须在带 Looper 的线程上创建和驱动，这里用一条专属 HandlerThread
 *  - 输出统一 H.264 + AAC（MP4），保证任何设备都能播放
 *  - 通过 DefaultEncoderFactory(setEnableFallback = true) 启用硬件编码器自动回退：
 *    硬件 H.264 不可用时由 Media3 自行降级，不会无限重试把手机拖垮
 *  - 进度来自 Transformer.getProgress()，是真实编码进度，不是假动画
 *  - 支持取消：cancel() 在 Looper 线程上调用，编码立即停止，原视频不受影响
 */
class CompressionEngine private constructor() {

    private val thread = HandlerThread("vc-transform").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var transformer: Transformer? = null

    @Volatile
    private var cancelledByUser = false

    /**
     * 执行压缩。
     *
     * @param sourceUri 原视频的 MediaStore Uri
     * @param outputFile 临时输出文件（验证通过前绝不覆盖原视频）
     * @param plan 编码参数
     * @param onProgress 0~99 的真实进度回调（在引擎线程触发，实现方需自行切换线程）
     */
    suspend fun compress(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        plan: CompressionPlan,
        onProgress: (Int) -> Unit
    ): CompressOutcome = suspendCancellableCoroutine { cont ->

        cancelledByUser = false

        handler.post {
            val finished = AtomicBoolean(false)
            var progressTask: Runnable? = null
            val appContext = context.applicationContext

            fun finish(outcome: CompressOutcome) {
                if (!finished.compareAndSet(false, true)) return
                progressTask?.let { handler.removeCallbacks(it) }
                transformer = null
                if (cont.isActive) cont.resume(outcome)
            }

            try {
                outputFile.parentFile?.mkdirs()
                if (outputFile.exists() && !outputFile.delete()) {
                    finish(
                        CompressOutcome.Failure(
                            ERR_TEMP_FILE,
                            "临时文件已存在且无法清理：${outputFile.name}"
                        )
                    )
                    return@post
                }

                // -------------------------------------------------- 输入
                val mediaItem = MediaItem.fromUri(sourceUri)

                // 分辨率下调通过 Presentation 实现（只缩小，不放大）。
                // 注意 plan.scaleWidth/scaleHeight 是「编码帧」尺寸：
                // Transformer 会保留源视频的 rotation，而 Presentation 作用于未旋转的解码帧，
                // 所以旋转视频的宽高映射已在 CompressionPlanFactory 中换算完毕。
                // 用 SCALE_TO_FIT_WITH_CROP 而不是 SCALE_TO_FIT：
                // 目标尺寸由源按比例算出，偶化后可能有 1~2 像素误差，
                // 裁剪掉这点边缘好过留下黑边（黑边既浪费码率也影响观感）。
                val videoEffects = mutableListOf<Effect>()
                if (plan.scaleWidth > 0 && plan.scaleHeight > 0) {
                    videoEffects += Presentation.createForWidthAndHeight(
                        plan.scaleWidth,
                        plan.scaleHeight,
                        Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                    )
                }

                val editedMediaItem = EditedMediaItem.Builder(mediaItem).apply {
                    if (videoEffects.isNotEmpty()) {
                        setEffects(Effects(emptyList(), videoEffects))
                    }
                }.build()

                // -------------------------------------------------- 编码器
                val encoderFactory = DefaultEncoderFactory.Builder(appContext)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(plan.videoBitrate)
                            .build()
                    )
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder()
                            .setBitrate(plan.audioBitrate.coerceAtLeast(64_000))
                            .build()
                    )
                    // 硬件编码器不支持目标格式时自动回退，而不是直接报错
                    .setEnableFallback(true)
                    .build()

                val request = TransformationRequest.Builder()
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .build()

                // 输出格式统一由 TransformationRequest 指定。
                // 不要再用 Transformer.Builder 的 setVideoMimeType/setAudioMimeType：
                // 它们在 1.5.x 已废弃，与 setTransformationRequest 并存时后者会覆盖前者，语义混乱。
                val built = Transformer.Builder(appContext)
                    .setTransformationRequest(request)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {

                        override fun onTransformationCompleted(
                            mediaItem: MediaItem,
                            result: TransformationResult
                        ) {
                            AppLog.i("ENCODE_COMPLETE", "编码完成 size=${outputFile.length()}")
                            finish(CompressOutcome.Success(outputFile.length()))
                        }

                        override fun onTransformationError(
                            mediaItem: MediaItem,
                            exception: TransformationException
                        ) {
                            when {
                                cancelledByUser -> {
                                    AppLog.i("ENCODE_CANCELLED", "用户取消，编码已停止")
                                    finish(CompressOutcome.Cancelled)
                                }

                                else -> {
                                    AppLog.e(
                                        "ERROR_CODE",
                                        "编码失败 code=${exception.errorCode} " +
                                            "name=${exception.getErrorCodeName()} " +
                                            "msg=${exception.message}"
                                    )
                                    finish(
                                        CompressOutcome.Failure(
                                            exception.errorCode,
                                            "编码失败（${exception.getErrorCodeName()}）：" +
                                                (exception.message ?: "未知原因")
                                        )
                                    )
                                }
                            }
                        }

                        override fun onFallbackApplied(
                            mediaItem: MediaItem,
                            original: TransformationRequest,
                            fallback: TransformationRequest
                        ) {
                            AppLog.w(
                                "FALLBACK_APPLIED",
                                "编码器回退 video=${fallback.videoMimeType} " +
                                    "audio=${fallback.audioMimeType}"
                            )
                        }
                    })
                    .build()

                transformer = built

                // -------------------------------------------------- 进度轮询
                val holder = ProgressHolder()
                val task = object : Runnable {
                    override fun run() {
                        if (finished.get()) return
                        runCatching {
                            when (built.getProgress(holder)) {
                                Transformer.PROGRESS_STATE_AVAILABLE ->
                                    onProgress(holder.progress.coerceIn(0, 99))

                                Transformer.PROGRESS_STATE_NO_TRANSFORMATION ->
                                    onProgress(99)

                                else -> Unit // 等待中/不可用：保持上一次进度，不假报数字
                            }
                        }
                        handler.postDelayed(this, PROGRESS_INTERVAL_MS)
                    }
                }
                progressTask = task
                handler.postDelayed(task, PROGRESS_INTERVAL_MS)

                AppLog.i(
                    "TASK_START",
                    "开始编码 uri=$sourceUri out=${outputFile.name} " +
                        "bitrate=${plan.videoBitrate} " +
                        "scaleFrame=${plan.scaleWidth}x${plan.scaleHeight}"
                )
                built.start(editedMediaItem, outputFile.absolutePath)
            } catch (e: Exception) {
                AppLog.e("ENGINE_INIT_FAILED", "引擎启动失败：${e.javaClass.simpleName} ${e.message}")
                finish(
                    CompressOutcome.Failure(
                        ERR_ENGINE_INIT,
                        "压缩引擎启动失败：${e.javaClass.simpleName} ${e.message}"
                    )
                )
            }
        }

        cont.invokeOnCancellation {
            cancelledByUser = true
            AppLog.i("ENCODE_CANCEL_REQUESTED", "收到取消请求")
            handler.post {
                runCatching { transformer?.cancel() }
            }
        }
    }

    /** 取消当前编码（线程安全，会在引擎线程执行） */
    fun cancel() {
        cancelledByUser = true
        handler.post {
            runCatching {
                transformer?.cancel()
                AppLog.i("ENCODE_CANCEL_CALL", "已调用 Transformer.cancel()")
            }
        }
    }

    fun shutdown() {
        runCatching { transformer?.cancel() }
        runCatching { thread.quitSafely() }
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 500L

        const val ERR_TEMP_FILE = 9001
        const val ERR_ENGINE_INIT = 9002

        fun create(): CompressionEngine = CompressionEngine()
    }
}
