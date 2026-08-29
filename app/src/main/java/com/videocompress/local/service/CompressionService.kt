package com.videocompress.local.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.videocompress.local.data.AppDatabase
import com.videocompress.local.data.AppSettings
import com.videocompress.local.data.SettingsRepository
import com.videocompress.local.data.TaskStatus
import com.videocompress.local.data.VideoTask
import com.videocompress.local.media.CompressOutcome
import com.videocompress.local.media.CompressionEngine
import com.videocompress.local.media.CompressionPlanFactory
import com.videocompress.local.media.DeleteResult
import com.videocompress.local.media.DeviceGuard
import com.videocompress.local.media.MediaStorePublisher
import com.videocompress.local.media.OutputVerifier
import com.videocompress.local.media.SourceExpectation
import com.videocompress.local.media.VideoProbe
import com.videocompress.local.media.VerifyResult
import com.videocompress.local.media.clearTempFiles
import com.videocompress.local.media.displayHeight
import com.videocompress.local.media.displayWidth
import com.videocompress.local.media.tempFileFor
import com.videocompress.local.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** 服务运行状态，供 UI 观察 */
object CompressionController {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal fun setRunning(value: Boolean) {
        _running.value = value
    }
}

/**
 * 视频压缩前台服务。
 *
 * 为什么必须是前台服务：
 *  - Activity + Thread / 普通 Coroutine / 普通后台 Service 在锁屏后都会被系统挂起或回收
 *  - Android 15+ 专门为「耗时媒体处理」提供了 mediaProcessing 类型的前台服务
 *
 * 关键约束：
 *  - 只在用户在 App 前台点击「开始压缩」时才启动，绝不偷偷自启
 *  - 同一时刻只编码 1 个视频（maxConcurrentTranscodes = 1）
 *  - 一个批次处理 2 个视频（batchSize = 2），串行为之，避免 CPU/GPU 资源竞争导致升温与编码器初始化失败
 *  - 任何异常都不删除原视频
 *  - 收到系统 onTimeout（Android 15 的 6 小时额度）立即保存状态并停止，绝不把任务标记成完成
 */
class CompressionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var database: AppDatabase
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var notifier: NotificationHelper

    private var engine: CompressionEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopJob: Job? = null

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var isLoopRunning = false

    // 进度节流。
    // 这两个字段会被引擎线程（onEngineProgress）和主循环同时读写，
    // 不加 @Volatile 时另一个线程可能一直读到旧值，导致进度要么卡住不动要么刷屏。
    @Volatile
    private var lastProgress = -1

    @Volatile
    private var lastProgressAt = 0L

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        settingsRepo = SettingsRepository(this)
        notifier = NotificationHelper(this)
        AppLog.init(this)
        CompressionController.setRunning(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.init(this)
        when (intent?.action) {
            ACTION_CANCEL -> {
                AppLog.i("SERVICE_CANCEL", "用户请求取消整个批次")
                cancelRequested = true
                engine?.cancel()
            }

            else -> startLoopIfNeeded()
        }
        // 不使用 START_STICKY：被系统杀掉后不自动重启，
        // 任务状态已经落库，用户重新打开 App 点「继续」即可，符合「不偷偷自启」的原则
        return START_NOT_STICKY
    }

    private fun startLoopIfNeeded() {
        if (isLoopRunning) {
            AppLog.w("SERVICE_ALREADY_RUNNING", "服务已在运行，忽略重复启动请求")
            return
        }
        isLoopRunning = true
        cancelRequested = false
        lastProgress = -1

        startForegroundSafe()
        acquireWakeLock()
        CompressionController.setRunning(true)

        loopJob = scope.launch {
            var summary = "处理中断，原视频均未删除"
            try {
                summary = runLoop()
            } catch (e: Exception) {
                // 协程被取消是正常收尾流程，不需要当成崩溃处理
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e(
                    "LOOP_CRASH",
                    "主循环异常退出：${e.javaClass.simpleName} ${e.message}"
                )
            } finally {
                isLoopRunning = false
                // 收尾必须放在 finally：
                // 早期版本把收尾写在 runLoop 末尾，一旦中途抛异常就全部跳过，
                // 结果是通知一直挂着、UI 永远显示「正在压缩」，只能手动划掉 App。
                // 顺序很关键：先 stopForeground 再发结果通知。
                // 如果先发结果通知再 stopForeground(REMOVE)，系统会把刚发的通知
                // 一起删掉，用户就永远看不到「完成 / 已取消 / 已暂停」的结果。
                stopForegroundSafely()
                notifier.finish(summary)
                AppLog.i("SERVICE_FINISHED", summary)
                stopSelf()
                CompressionController.setRunning(false)
            }
        }
    }

    private fun startForegroundSafe() {
        val notification = notifier.buildInitial()
        try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
            AppLog.i("FGS_STARTED", "前台服务已启动（mediaProcessing）")
        } catch (e: Exception) {
            // 某些厂商 ROM 会对带类型的启动做额外校验，失败时退化为不带类型的普通前台服务
            AppLog.e("FGS_START_FAILED", "带类型启动失败，尝试降级：${e.javaClass.simpleName}")
            runCatching { startForeground(NotificationHelper.NOTIFICATION_ID, notification) }
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoCompressor:Encode")
                ?.apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    // ------------------------------------------------------------------ 主循环

    /**
     * 执行主循环，返回给用户的结束摘要。
     * 注意：这里不做任何收尾（通知/停止前台/停止服务），收尾统一由调用方的 finally 负责。
     */
    private suspend fun runLoop(): String {
        val settings = settingsRepo.settings.first()
        val dao = database.taskDao()

        clearTempFiles(this)
        // 上次被系统打断的「待处理」任务重新入队（状态完整，不会重复压缩已完成的）
        dao.requeueInterrupted()

        AppLog.i(
            "SERVICE_START",
            "压缩服务启动 batchSize=${settings.batchSize} quality=${settings.quality.name} " +
                "resolution=${settings.resolution.name}"
        )

        var processed = 0
        var failed = 0
        // 因设备状态（低电量 / 过热 / 未充电）主动停下时，摘要要说清楚原因，
        // 否则用户只看到「完成 N 个」，会以为队列已经跑完了
        var pauseReason: String? = null

        while (!cancelRequested && currentCoroutineContext().isActive) {
            val batch = dao.takeWaiting(settings.batchSize.coerceIn(1, 4))
            if (batch.isEmpty()) break

            AppLog.i("BATCH_START", "批次开始 size=${batch.size} ids=${batch.map { it.id }}")

            batch.forEachIndexed { index, task ->
                if (cancelRequested || !currentCoroutineContext().isActive) return@forEachIndexed
                val result = processOne(task, settings, index + 1, batch.size)
                when (result) {
                    StepResult.Success -> processed++
                    StepResult.NoGain -> processed++
                    StepResult.Skipped -> Unit
                    StepResult.Failed -> failed++
                    StepResult.Cancelled -> Unit
                }
            }

            if (cancelRequested) break

            // 每批之间检查设备状态：电量 / 温度 / 充电
            val stop = DeviceGuard.shouldStopBeforeNextBatch(this@CompressionService, settings)
            if (stop != null) {
                AppLog.w("SERVICE_PAUSE", "${stop.code} :: ${stop.message}")
                pauseReason = stop.message
                break
            }
        }

        return when {
            cancelRequested -> "已取消，原视频均未删除"

            pauseReason != null ->
                "已暂停：$pauseReason" +
                    if (processed > 0) "（本次已完成 $processed 个）" else ""

            processed == 0 && failed == 0 -> "没有待处理的视频"

            else -> "完成 $processed 个" +
                if (failed > 0) "，失败 $failed 个（原视频已保留）" else ""
        }
    }

    private enum class StepResult { Success, NoGain, Skipped, Failed, Cancelled }

    // ------------------------------------------------------------------ 单任务

    private suspend fun processOne(
        task: VideoTask,
        settings: AppSettings,
        indexInBatch: Int,
        batchSize: Int
    ): StepResult {
        val dao = database.taskDao()
        val startedAt = System.currentTimeMillis()
        val previousRetries = task.retryCount

        dao.updateStatus(
            id = task.id,
            status = TaskStatus.PROCESSING.name,
            progress = 0,
            message = null,
            code = null,
            startedAt = startedAt,
            finishedAt = null,
            retryCount = previousRetries + 1
        )
        lastProgress = -1
        notifier.updateTask(task.originalName, 0, indexInBatch, batchSize)

        val uri = Uri.parse(task.originalUri)
        val tempFile: File = tempFileFor(this, task.id)

        return try {
            // -------------------------------------------------- 1. 原视频还在吗
            if (!MediaStorePublisher.isUriAlive(this, uri)) {
                finishAs(dao, task, TaskStatus.FAILED, ERR_SOURCE_GONE,
                    "原视频已不存在或无法访问（未做任何删除）", startedAt, previousRetries + 1)
                return StepResult.Failed
            }

            // -------------------------------------------------- 2. 安全探测
            val probe = VideoProbe.probe(this, uri)
            if (probe == null) {
                finishAs(dao, task, TaskStatus.SKIPPED, ERR_PROBE,
                    "无法解析该视频，已跳过并保留原视频", startedAt, previousRetries + 1)
                return StepResult.Skipped
            }
            if (!probe.hasVideo) {
                finishAs(dao, task, TaskStatus.SKIPPED, ERR_NO_VIDEO_TRACK,
                    "该视频没有视频轨，已跳过并保留原视频", startedAt, previousRetries + 1)
                return StepResult.Skipped
            }
            if (settings.skipHdr && probe.isHdr) {
                finishAs(dao, task, TaskStatus.SKIPPED, ERR_HDR,
                    "此视频为 HDR / 10bit，暂不支持安全压缩，已跳过", startedAt, previousRetries + 1)
                return StepResult.Skipped
            }
            if (settings.skipUnsupportedCodec && !VideoProbe.isSupportedCodec(probe.videoMime)) {
                finishAs(dao, task, TaskStatus.SKIPPED, ERR_CODEC,
                    "视频编码 ${probe.videoMime ?: "未知"} 暂不支持安全压缩，已跳过", startedAt, previousRetries + 1)
                return StepResult.Skipped
            }

            // -------------------------------------------------- 3. 编码计划
            val plan = CompressionPlanFactory.create(probe, task.originalSize, settings)
            if (plan == null) {
                finishAs(dao, task, TaskStatus.SKIPPED, ERR_NO_BENEFIT,
                    "按当前设置无法有效压缩，已跳过并保留原视频", startedAt, previousRetries + 1)
                return StepResult.Skipped
            }

            // -------------------------------------------------- 4. 存储预检
            val storage = DeviceGuard.checkStorage(this, plan.estimatedOutputBytes)
            if (storage != null) {
                finishAs(dao, task, TaskStatus.FAILED, ERR_STORAGE, storage.message, startedAt, previousRetries + 1)
                return StepResult.Failed
            }

            // -------------------------------------------------- 5. 压缩
            val progressSink: (Int) -> Unit = { p ->
                onEngineProgress(task.id, p, task.originalName, indexInBatch, batchSize)
            }
            var outcome = ensureEngine().compress(this, uri, tempFile, plan, progressSink)

            // 只重试一次：无限回退会把手机拖垮
            if (outcome is CompressOutcome.Failure && previousRetries < MAX_RETRY && !cancelRequested) {
                AppLog.w("RETRY_ONCE", "首次编码失败，重试一次 code=${outcome.errorCode}")
                delay(1500)
                lastProgress = -1
                // 重建引擎再重试：编码失败后 Transformer 内部状态可能已经脏了，
                // 复用同一个实例往往会让第二次尝试以完全相同的方式再失败一次，
                // 白白多等一个视频的编码时间。
                resetEngine()
                outcome = ensureEngine().compress(this, uri, tempFile, plan, progressSink)
            }

            when (outcome) {
                is CompressOutcome.Cancelled -> {
                    finishAs(dao, task, TaskStatus.CANCELLED, ERR_CANCELLED,
                        "用户取消，原视频已保留", startedAt, previousRetries + 1)
                    return StepResult.Cancelled
                }

                is CompressOutcome.Failure -> {
                    finishAs(dao, task, TaskStatus.FAILED, outcome.errorCode,
                        outcome.message, startedAt, previousRetries + 1)
                    return StepResult.Failed
                }

                is CompressOutcome.Success -> Unit
            }

            // -------------------------------------------------- 6. 验证临时文件
            dao.updateStatus(task.id, TaskStatus.VERIFYING.name, 100, null, null,
                startedAt, null, previousRetries + 1)

            // 用「本次真实探测结果」而不是扫描时的快照做校验基准，
            // 避免旋转、时长、音轨等元数据在扫描阶段没有解析出来导致误判
            val expectation = SourceExpectation(
                size = task.originalSize,
                durationMs = if (probe.durationMs > 0) probe.durationMs else task.durationMs,
                displayWidth = probe.displayWidth,
                displayHeight = probe.displayHeight,
                hasAudio = probe.hasAudio
            )

            when (val verify = OutputVerifier.verifyFile(
                this, tempFile, expectation, settings.minSavingsPercent
            )) {
                is VerifyResult.Invalid -> {
                    AppLog.e("VERIFY_FAILED", "${verify.code} :: ${verify.reason}")
                    finishAs(dao, task, TaskStatus.FAILED, ERR_VERIFY,
                        "输出文件验证未通过，已保留原视频：${verify.reason}", startedAt, previousRetries + 1)
                    return StepResult.Failed
                }

                is VerifyResult.NoGain -> {
                    AppLog.i("NO_GAIN", verify.reason)
                    finishAs(dao, task, TaskStatus.COMPLETED_NO_GAIN, null, verify.reason, startedAt, previousRetries + 1)
                    return StepResult.NoGain
                }

                is VerifyResult.Ok -> Unit
            }

            // -------------------------------------------------- 7. 发布到相册
            val outputUri = MediaStorePublisher.publish(this, tempFile, task.originalName)
            if (outputUri == null) {
                finishAs(dao, task, TaskStatus.FAILED, ERR_PUBLISH,
                    "写入相册失败，已保留原视频", startedAt, previousRetries + 1)
                return StepResult.Failed
            }

            // -------------------------------------------------- 8. 二次验证最终文件
            when (val finalVerify = OutputVerifier.verifyUri(
                this, outputUri, expectation, settings.minSavingsPercent
            )) {
                is VerifyResult.Ok -> {
                    // 先落库，再删原视频：
                    // 绝不允许出现「原视频已删除但数据库还是 PENDING」的灾难状态
                    dao.updateOutput(task.id, outputUri.toString(),
                        MediaStorePublisher.outputNameFor(task.originalName), finalVerify.size)
                    dao.updateStatus(task.id, TaskStatus.COMPLETED.name, 100, null, null,
                        startedAt, System.currentTimeMillis(), previousRetries + 1)
                    AppLog.i("TASK_COMPLETE", "任务完成 id=${task.id} 已节省 " +
                        "${(task.originalSize - finalVerify.size) / 1024}KB")
                }

                else -> {
                    // 最终文件不合格 → 立刻删掉刚发布的文件，原视频保持不变
                    runCatching { contentResolver.delete(outputUri, null, null) }
                    val reason = when (finalVerify) {
                        is VerifyResult.Invalid -> finalVerify.reason
                        is VerifyResult.NoGain -> finalVerify.reason
                        else -> "最终文件验证未通过"
                    }
                    AppLog.e("FINAL_VERIFY_FAILED", reason)
                    finishAs(dao, task, TaskStatus.FAILED, ERR_FINAL_VERIFY,
                        "最终文件验证未通过，已删除输出并保留原视频：$reason", startedAt, previousRetries + 1)
                    return StepResult.Failed
                }
            }

            // -------------------------------------------------- 9. 删除原视频
            if (!settings.deleteOriginal) {
                AppLog.i("KEEP_ORIGINAL", "设置为保留原视频 id=${task.id}")
                return StepResult.Success
            }

            when (val delete = MediaStorePublisher.deleteVideo(this, uri)) {
                is DeleteResult.Ok -> Unit

                is DeleteResult.NeedConsent -> {
                    // 系统要求用户确认。这不算失败：压缩已经成功，
                    // 只把「删除原视频」这一步挂起，等用户在 UI 里确认，绝不重新压缩
                    dao.updateStatus(
                        id = task.id,
                        status = TaskStatus.COMPLETED_BUT_ORIGINAL_DELETE_FAILED.name,
                        progress = 100,
                        message = "压缩成功，需要在系统弹窗中确认删除原视频",
                        code = ERR_DELETE_NEED_CONSENT,
                        startedAt = startedAt,
                        finishedAt = System.currentTimeMillis(),
                        retryCount = previousRetries + 1
                    )
                    AppLog.w("DELETE_NEED_CONSENT", "需要用户确认 id=${task.id}")
                }

                is DeleteResult.Failed -> {
                    dao.updateStatus(
                        id = task.id,
                        status = TaskStatus.COMPLETED_BUT_ORIGINAL_DELETE_FAILED.name,
                        progress = 100,
                        message = "压缩成功，但删除原视频失败：${delete.message}",
                        code = ERR_DELETE_FAILED,
                        startedAt = startedAt,
                        finishedAt = System.currentTimeMillis(),
                        retryCount = previousRetries + 1
                    )
                    AppLog.w("DELETE_ORIGINAL_FAILED", "删除失败 id=${task.id} ${delete.message}")
                }
            }

            StepResult.Success
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.e("UNEXPECTED", "未预期异常 id=${task.id} ${e.javaClass.simpleName} ${e.message}")
            finishAs(dao, task, TaskStatus.FAILED, ERR_UNEXPECTED,
                "未预期异常，已保留原视频：${e.message}", startedAt, previousRetries + 1)
            StepResult.Failed
        } finally {
            // 临时文件无论成功失败都清理：成功时最终文件已经发布并验证过
            runCatching { if (tempFile.exists()) tempFile.delete() }
        }
    }

    /**
     * 统一的终态写入。
     *
     * [retryCount] 必须由调用方显式传入「本次尝试后的真实次数」：
     * 早期版本直接写回 task.retryCount（循环开始时的旧快照），
     * 会把 processOne 开头刚递增的次数又覆盖回去，导致重试计数永远停在初始值，
     * 失败任务看上去「一次都没重试过」。
     */
    private suspend fun finishAs(
        dao: com.videocompress.local.data.TaskDao,
        task: VideoTask,
        status: TaskStatus,
        code: Int?,
        message: String,
        startedAt: Long,
        retryCount: Int
    ) {
        dao.updateStatus(
            id = task.id,
            status = status.name,
            progress = if (status == TaskStatus.COMPLETED) 100 else task.progress,
            message = message,
            code = code,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            retryCount = retryCount
        )
        AppLog.i("TASK_${status.name}", "id=${task.id} code=$code :: $message")
    }

    /** 引擎进度回调（在引擎线程触发） */
    private fun onEngineProgress(
        taskId: Long,
        progress: Int,
        name: String,
        index: Int,
        total: Int
    ) {
        val now = System.currentTimeMillis()
        if (progress != 0 && progress != 100 &&
            (progress < lastProgress + 2 || now - lastProgressAt < 400)
        ) return
        lastProgress = progress
        lastProgressAt = now

        scope.launch { runCatching { database.taskDao().updateProgress(taskId, progress) } }
        runCatching { notifier.updateTask(name, progress, index, total) }
    }

    private fun ensureEngine(): CompressionEngine {
        return engine ?: CompressionEngine.create().also { engine = it }
    }

    /** 丢弃当前引擎，下次 ensureEngine() 会新建一个干净的实例 */
    private fun resetEngine() {
        runCatching { engine?.shutdown() }
        engine = null
    }

    // ------------------------------------------------------------------ 生命周期

    /** Android 8+：前台服务超时 */
    override fun onTimeout(startId: Int) = handleTimeout()

    /** Android 15+：mediaProcessing 前台服务 24 小时内累计 6 小时额度耗尽 */
    override fun onTimeout(startId: Int, fgsType: Int) = handleTimeout()

    private fun handleTimeout() {
        AppLog.w("FGS_TIMEOUT", "前台服务达到系统时间上限，立即保存状态并停止")
        cancelRequested = true
        engine?.cancel()
        // 把「处理中」恢复成「已中断」，绝不能标记成 COMPLETED，更不会删原视频。
        // 这里用异步而不是 runBlocking：onTimeout 是主线程回调，
        // 硬等数据库写入会白白占住主线程，任务多时有 ANR 风险。
        scope.launch {
            runCatching { database.taskDao().resetBusyToInterrupted() }
        }
        // 先 detach 前台通知，再发结果通知，否则结果通知会被一起删掉
        stopForegroundSafely()
        notifier.updateStopped("已达到系统后台处理时间上限，任务已保存，可继续处理")
        stopSelf()
        CompressionController.setRunning(false)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉 App：不做任何拉活动作，只记录状态
        AppLog.w("TASK_REMOVED", "用户从最近任务移除 App")
        super.onTaskRemoved(rootIntent)
    }

    private fun stopForegroundSafely() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    override fun onDestroy() {
        AppLog.i("SERVICE_DESTROY", "服务销毁，释放资源")
        loopJob?.cancel()
        engine?.shutdown()
        engine = null
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null

        // 把仍在「处理中 / 验证中」的任务复位成「已中断」。
        // 少了这一步，服务被系统回收后任务会永远卡在「压缩中」：
        // 用户既看不到进度，也没有任何入口可以重试，只能清数据重装。
        // 注意：删除原视频那一步之前任务已经是 COMPLETED 状态，这里不会误伤。
        scope.launch {
            runCatching { database.taskDao().resetBusyToInterrupted() }
        }

        clearTempFiles(this)
        CompressionController.setRunning(false)
        super.onDestroy()
    }

    companion object {

        private const val ACTION_CANCEL = "com.videocompress.local.ACTION_CANCEL"
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L
        private const val MAX_RETRY = 1

        // 错误码
        const val ERR_SOURCE_GONE = 8001
        const val ERR_PROBE = 8002
        const val ERR_NO_VIDEO_TRACK = 8003
        const val ERR_HDR = 8004
        const val ERR_CODEC = 8005
        const val ERR_NO_BENEFIT = 8006
        const val ERR_STORAGE = 8007
        const val ERR_VERIFY = 8008
        const val ERR_PUBLISH = 8009
        const val ERR_FINAL_VERIFY = 8010
        const val ERR_CANCELLED = 8011
        const val ERR_DELETE_NEED_CONSENT = 8012
        const val ERR_DELETE_FAILED = 8013
        const val ERR_UNEXPECTED = 8099

        fun startIntent(context: Context): Intent =
            Intent(context, CompressionService::class.java)

        fun cancelIntent(context: Context): Intent =
            Intent(context, CompressionService::class.java).setAction(ACTION_CANCEL)

        /** 用户在 App 前台点击「开始压缩」时才调用 —— 绝不自动启动 */
        fun start(context: Context) {
            AppLog.init(context)
            AppLog.i("FGS_START_REQUESTED", "用户点击开始压缩")
            runCatching {
                context.startForegroundService(startIntent(context))
            }.onFailure {
                AppLog.e("FGS_REQUEST_FAILED", "启动前台服务失败：${it.message}")
                runCatching { context.startService(startIntent(context)) }
            }
        }

        /** 取消整个批次 */
        fun cancel(context: Context) {
            runCatching {
                context.startService(cancelIntent(context))
            }.onFailure {
                AppLog.e("CANCEL_FAILED", "发送取消请求失败：${it.message}")
            }
        }
    }
}
