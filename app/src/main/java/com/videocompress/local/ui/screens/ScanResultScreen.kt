package com.videocompress.local.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videocompress.local.data.SkipReason
import com.videocompress.local.data.SkippedVideo
import com.videocompress.local.data.SyncResult
import com.videocompress.local.ui.HomeViewModel
import com.videocompress.local.ui.components.FlatCard
import com.videocompress.local.ui.components.HintText
import com.videocompress.local.util.formatBytes
import com.videocompress.local.util.formatDuration
import com.videocompress.local.util.formatResolution

/**
 * 扫描结果页：把「对不上」的视频单独列出来，让用户决定去留。
 *
 * 重点展示两类被跳过的视频：
 *  1. 已在队列中（按 originalUri 去重）—— 可移除任务
 *  2. 低于「最小大小」设置 —— 可去设置页调低阈值
 *
 * 本页不删除原视频文件，只操作任务数据库，保证安全。
 */
@Composable
fun ScanResultScreen(
    vm: HomeViewModel,
    result: SyncResult,
    onOpenSettings: () -> Unit
) {
    val already = result.skipped.filter { it.reason == SkipReason.ALREADY_IN_QUEUE }
    val tooSmall = result.skipped.filter { it.reason == SkipReason.BELOW_MIN_SIZE }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ScanSummaryCard(result) }

            if (already.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "已在队列中（重复）",
                        count = already.size,
                        hint = "这些视频已经存在于任务队列，不会再被压缩。" +
                            "你可以点击「移除任务」把它们清出队列；原视频文件不会被删除。"
                    )
                }
                items(items = already, key = { it.uri }) { video ->
                    SkippedVideoRow(
                        video = video,
                        actionLabel = "移除任务",
                        actionIcon = Icons.Default.Delete,
                        onAction = {
                            // 安全做法：仅通过 uri 关联的任务 id 删除，
                            // 但 SkippedVideo 没有 id，所以这里让 ViewModel 按 uri 查找并删除。
                            vm.removeTaskByUri(video.uri)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            if (tooSmall.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "低于最小大小限制",
                        count = tooSmall.size,
                        hint = "当前设置「最小视频大小」为 ${result.minSizeMb} MB，" +
                            "这些视频因太小而被忽略。点击「调整设置」可降低阈值把它们纳入队列。"
                    )
                }
                items(items = tooSmall, key = { it.uri }) { video ->
                    SkippedVideoRow(
                        video = video,
                        actionLabel = "调整设置",
                        actionIcon = Icons.Default.Settings,
                        onAction = onOpenSettings
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            if (result.skipped.isEmpty()) {
                item {
                    EmptyState(text = "本次扫描没有跳过任何视频，全部已加入队列。")
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ScanSummaryCard(result: SyncResult) {
    FlatCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "扫描结果",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryTile(modifier = Modifier.weight(1f), label = "相册视频", value = result.total.toString())
                SummaryTile(modifier = Modifier.weight(1f), label = "新增任务", value = result.added.toString())
                SummaryTile(modifier = Modifier.weight(1f), label = "已跳过", value = result.skipped.size.toString())
            }
        }
    }
}

@Composable
private fun SummaryTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    hint: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            HintText(hint)
        }
    }
}

@Composable
private fun SkippedVideoRow(
    video: SkippedVideo,
    actionLabel: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatBytes(video.size)} · " +
                        "${formatResolution(video.width, video.height, 0)} · " +
                        formatDuration(video.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAction, modifier = Modifier.height(32.dp)) {
                Icon(
                    actionIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HintText(text)
    }
}
