package com.videocompress.local.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videocompress.local.data.AppDatabase
import com.videocompress.local.data.AppSettings
import com.videocompress.local.data.LogEntry
import com.videocompress.local.data.SettingsRepository
import com.videocompress.local.data.TaskCounts
import com.videocompress.local.data.TaskRepository
import com.videocompress.local.data.VideoTask
import com.videocompress.local.media.MediaStoreScanner
import com.videocompress.local.service.CompressionController
import com.videocompress.local.service.CompressionService
import com.videocompress.local.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 / 任务 / 设置 / 日志 共用的 ViewModel。
 *
 * UI 只负责三件事：显示状态、发送命令、接收进度。
 * 所有扫描、压缩、数据库读写都在 IO 线程，绝不阻塞主线程。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Application = app
    private val repository = TaskRepository(app)
    private val settingsRepository = SettingsRepository(app)
    private val logDao = AppDatabase.getInstance(app).logDao()

    /** App 设置 */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** 统计：已发现 / 待处理 / 已完成 / 失败 / 已节省 */
    val counts: StateFlow<TaskCounts> = repository.observeCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskCounts())

    /** 首页「当前批次」：队列最前面的 2 个待处理任务 */
    val batch: StateFlow<List<VideoTask>> = repository.observeWaiting(BATCH_SIZE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全部任务（任务页） */
    val tasks: StateFlow<List<VideoTask>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 日志（最近 400 条） */
    val logs: StateFlow<List<LogEntry>> = logDao.observeRecent(400)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 压缩服务是否在运行 */
    val running: StateFlow<Boolean> = CompressionController.running
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 当前正在处理的任务（如果有） */
    val activeTask: StateFlow<VideoTask?> = repository.observeAll()
        .map { list -> list.firstOrNull { it.status.isBusy } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // ------------------------------------------------------------------ 扫描

    fun scan() {
        if (_isScanning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            AppLog.i("SCAN_REQUESTED", "用户触发扫描")
            runCatching {
                val videos = MediaStoreScanner.scan(context)
                val result = repository.syncFromMediaStore(videos, settings.value)
                _toast.value = "发现 ${videos.size} 个视频，新增 ${result.added} 个任务"
            }.onFailure {
                AppLog.e("SCAN_ERROR", "扫描失败：${it.message}")
                _toast.value = "扫描失败：${it.message}"
            }
            _isScanning.value = false
        }
    }

    // ------------------------------------------------------------------ 压缩控制

    /** 用户在 App 前台点击「开始压缩」 */
    fun startCompression() {
        viewModelScope.launch(Dispatchers.IO) {
            // 先重排一次，保证顺序与当前设置一致
            runCatching { repository.requeueInterrupted() }
            runCatching { repository.reorder(settings.value) }
            CompressionService.start(context)
        }
    }

    /** 取消整个批次 */
    fun cancelCompression() {
        CompressionService.cancel(context)
        _toast.value = "正在停止，当前视频处理完成后会停止下一批"
    }

    // ------------------------------------------------------------------ 任务操作

    fun retryFailed() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.retryAllFailed()
            _toast.value = "失败任务已重新排队"
        }
    }

    fun retryOne(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repository.retryOne(id) }
    }

    /** 用户在系统弹窗中确认删除原视频之后，刷新任务状态 */
    fun refreshDeleteStates() {
        viewModelScope.launch(Dispatchers.IO) {
            val finalized = runCatching { repository.refreshPendingDeleteTasks() }.getOrDefault(0)
            if (finalized > 0) _toast.value = "已确认删除 $finalized 个原视频"
        }
    }

    /** 需要系统确认删除的原视频任务 */
    suspend fun pendingDeleteTasks() = repository.pendingDeleteTasks()

    // ------------------------------------------------------------------ 设置

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val before = settingsRepository.settings.first()
                settingsRepository.update(transform)
                val after = settingsRepository.settings.first()
                // 只有排序方式真的变了才重排队列。
                // 设置页里拖滑块时每动一下都会走到这里，无脑重排等于每个任务写一次数据库，
                // 一次拖动就能刷出成百上千条 UPDATE。
                if (before.sortOrder != after.sortOrder) {
                    repository.reorder(after)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 日志

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) { logDao.clear() }
    }

    fun consumeToast() {
        _toast.value = null
    }

    companion object {
        private const val BATCH_SIZE = 2
    }
}
