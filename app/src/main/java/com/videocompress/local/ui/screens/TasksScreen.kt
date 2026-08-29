package com.videocompress.local.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videocompress.local.data.TaskStatus
import com.videocompress.local.data.VideoTask
import com.videocompress.local.media.MediaStorePublisher
import com.videocompress.local.ui.HomeViewModel
import com.videocompress.local.ui.components.HintText
import com.videocompress.local.ui.components.StatusChip
import com.videocompress.local.util.formatBytes
import com.videocompress.local.util.formatDuration
import com.videocompress.local.util.formatResolution
import com.videocompress.local.util.savingPercent
import kotlinx.coroutines.launch

private enum class TaskFilter(val label: String) {
    ALL("全部"),
    WAITING("待处理"),
    DONE("已完成"),
    FAILED("失败/跳过")
}

/**
 * 任务列表页。
 *
 * 每个任务的状态都来自数据库（不是内存变量），
 * 所以 App 被杀、手机重启之后打开这里，状态依然是准确的。
 */
@Composable
fun TasksScreen(vm: HomeViewModel) {

    val context = LocalContext.current
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(TaskFilter.ALL) }
    val scope = rememberCoroutineScope()

    // 系统批量删除确认弹窗（Android 11+）
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            vm.refreshDeleteStates()
        }
    }

    val filtered = remember(tasks, filter) {
        when (filter) {
            TaskFilter.ALL -> tasks
            TaskFilter.WAITING -> tasks.filter { it.status.isWaiting || it.status.isBusy }
            TaskFilter.DONE -> tasks.filter { it.status.isCompressionSucceeded }
            TaskFilter.FAILED -> tasks.filter {
                it.status == TaskStatus.FAILED ||
                    it.status == TaskStatus.CANCELLED ||
                    it.status == TaskStatus.SKIPPED
            }
        }
    }

    val failedCount = remember(tasks) {
        tasks.count { it.status == TaskStatus.FAILED }
    }
    val pendingDelete = remember(tasks) {
        tasks.count { it.status == TaskStatus.COMPLETED_BUT_ORIGINAL_DELETE_FAILED }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ------------------------------------------------------------ 过滤器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TaskFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        // ------------------------------------------------------------ 批量操作
        if (failedCount > 0 || pendingDelete > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (failedCount > 0) {
                    FilledTonalButton(onClick = { vm.retryFailed() }, modifier = Modifier.height(36.dp)) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("重试 $failedCount 个失败任务", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (pendingDelete > 0) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val targets = vm.pendingDeleteTasks()
                                val uris = targets.map { Uri.parse(it.originalUri) }
                                val sender =
                                    MediaStorePublisher.createBatchDeleteRequest(context, uris)
                                if (sender != null) {
                                    deleteLauncher.launch(
                                        IntentSenderRequest.Builder(sender).build()
                                    )
                                } else {
                                    // 低于 Android 11：提示用户前往设置手动处理，不做危险操作
                                    vm.retryOne(targets.first().id)
                                }
                            }
                        },
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("确认删除 $pendingDelete 个原视频", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HintText("这里还没有任务。回到首页点「重新扫描」即可自动识别相册里的视频。")
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items = filtered, key = { it.id }) { task ->
                TaskRow(
                    task = task,
                    onRetry = { vm.retryOne(task.id) },
                    onOpenOutput = {
                        task.outputUri?.let { uri ->
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(uri), "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            }
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TaskRow(
    task: VideoTask,
    onRetry: () -> Unit,
    onOpenOutput: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.originalName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${formatBytes(task.originalSize)} · " +
                        "${formatResolution(task.width, task.height, task.rotation)} · " +
                        formatDuration(task.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(task.status)
        }

        // 输出结果
        if (task.outputSize > 0) {
            val percent = savingPercent(task.originalSize, task.outputSize)
            Text(
                text = if (percent > 0) {
                    "输出 ${formatBytes(task.outputSize)} · 节省 $percent%"
                } else {
                    "输出 ${formatBytes(task.outputSize)} · 无收益"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 进行中进度
        if (task.status.isBusy) {
            LinearProgressIndicator(
                progress = task.progress.coerceIn(0, 100) / 100f,
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }

        // 错误 / 说明
        task.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 操作
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (task.status == TaskStatus.FAILED || task.status == TaskStatus.CANCELLED) {
                TextButton(onClick = onRetry, modifier = Modifier.height(32.dp)) {
                    Text("重新排队")
                }
            }
            if (task.outputUri != null) {
                TextButton(onClick = onOpenOutput, modifier = Modifier.height(32.dp)) {
                    Text("播放输出文件")
                }
            }
        }
    }
}
