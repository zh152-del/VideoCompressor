package com.videocompress.local.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videocompress.local.data.AppSettings
import com.videocompress.local.data.SkipReason
import com.videocompress.local.data.SyncResult
import com.videocompress.local.data.VideoTask
import com.videocompress.local.ui.HomeViewModel
import com.videocompress.local.ui.components.FlatCard
import com.videocompress.local.ui.components.HintText
import com.videocompress.local.ui.components.SectionTitle
import com.videocompress.local.ui.components.StatusChip
import com.videocompress.local.ui.components.VideoThumbnail
import com.videocompress.local.util.formatBytes
import com.videocompress.local.util.formatDuration
import com.videocompress.local.util.formatResolution

/**
 * 首页：统计 + 当前批次 + 开始压缩。
 *
 * 刻意做得简单：一屏能看到「还剩多少要压、正在压什么、按一下就开始」。
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenGuide: () -> Unit,
    onOpenScanResult: () -> Unit
) {

    val counts by vm.counts.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()
    val active by vm.activeTask.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val isScanning by vm.isScanning.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scanResult by vm.lastScanResult.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // ------------------------------------------------------------ 统计
        FlatCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("已发现 ${counts.total} 个视频")
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { vm.scan() },
                    enabled = !isScanning,
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isScanning) "扫描中" else "重新扫描", fontSize = 13.sp)
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = "待处理",
                    value = counts.waiting.toString(),
                    emphasize = true
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = "已完成",
                    value = counts.done.toString()
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = "失败",
                    value = counts.failed.toString()
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "累计节省",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatBytes(counts.savedBytes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ------------------------------------------------------------ 扫描结果（重复/已过滤入口）
        ScanSummaryCard(scanResult, onOpenScanResult)

        // ------------------------------------------------------------ 当前批次
        FlatCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("当前批次")
                Spacer(Modifier.weight(1f))
                HintText("每批 ${settings.batchSize} 个 · 同时只编码 1 个")
            }

            val slots = buildBatchSlots(active, batch, settings.batchSize)
            slots.forEachIndexed { index, task ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                BatchSlot(index = index + 1, task = task)
            }
        }

        // ------------------------------------------------------------ 操作
        if (running) {
            OutlinedButton(
                onClick = { vm.cancelCompression() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("停止（保留原视频）")
            }
        } else {
            Button(
                onClick = { vm.startCompression() },
                enabled = counts.waiting > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    text = if (counts.waiting > 0) "开始压缩（${counts.waiting}）" else "没有待处理视频",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // ------------------------------------------------------------ 说明
        FlatCard {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HintText(
                        "支持后台及锁屏处理，但系统或厂商后台策略可能中断任务。" +
                            "任务状态会实时保存，中断后重新打开可继续。"
                    )
                    HintText(
                        "压缩成功并通过完整校验后才会删除原视频；" +
                            "任何一步失败都会原样保留你的视频。"
                    )
                    FilledTonalButton(
                        onClick = onOpenGuide,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("VIVO / OriginOS 后台运行设置指南", fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** 组合出当前批次要展示的任务槽位 */
private fun buildBatchSlots(
    active: VideoTask?,
    waiting: List<VideoTask>,
    batchSize: Int
): List<VideoTask?> {
    val size = batchSize.coerceAtLeast(1)
    val result = mutableListOf<VideoTask?>()
    if (active != null) {
        result += active
        result += waiting.filter { it.id != active.id }.take(size - 1)
    } else {
        result += waiting.take(size)
    }
    while (result.size < size) result += null
    return result
}

@Composable
private fun BatchSlot(index: Int, task: VideoTask?) {
    if (task == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.VideoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "视频 $index：暂无",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val busy = task.status.isBusy
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VideoThumbnail(uri = Uri.parse(task.originalUri), sizeDp = 48)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "视频 $index",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = task.originalName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusChip(task.status)
        }

        Text(
            text = "${formatBytes(task.originalSize)} · " +
                "${formatResolution(task.width, task.height, task.rotation)} · " +
                formatDuration(task.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (busy) {
            val progress = (task.progress.coerceIn(0, 100)) / 100f
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
            Text(
                text = if (task.status.name == "VERIFYING") "正在验证输出文件…" else "${task.progress}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    emphasize: Boolean = false
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (emphasize) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 首页扫描结果摘要：发现 / 新增 / 已跳过，点击可进入详情决定去留 */
@Composable
private fun ScanSummaryCard(
    result: SyncResult?,
    onOpenScanResult: () -> Unit
) {
    if (result == null || result.total == 0) return

    val alreadyCount = result.countBy(SkipReason.ALREADY_IN_QUEUE)
    val tooSmallCount = result.countBy(SkipReason.BELOW_MIN_SIZE)

    FlatCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("扫描结果")
                Spacer(Modifier.weight(1f))
                if (result.skipped.isNotEmpty()) {
                    TextButton(onClick = onOpenScanResult) {
                        Text("查看 ${result.skipped.size} 个")
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = "相册视频",
                    value = result.total.toString()
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = "新增任务",
                    value = result.added.toString(),
                    emphasize = true
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = "已跳过",
                    value = result.skipped.size.toString(),
                    emphasize = result.skipped.size > 0
                )
            }

            if (result.skipped.isNotEmpty()) {
                HintText(
                    "其中 ${alreadyCount} 个已在队列（重复），${tooSmallCount} 个低于 ${result.minSizeMb} MB 限制，" +
                        "点击查看后可选择移除重复任务。"
                )
            }
        }
    }
}
