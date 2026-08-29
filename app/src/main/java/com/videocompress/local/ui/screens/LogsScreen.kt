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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videocompress.local.data.LogEntry
import com.videocompress.local.ui.HomeViewModel
import com.videocompress.local.ui.components.HintText
import com.videocompress.local.util.formatClock
import com.videocompress.local.util.formatTime

/**
 * 运行日志。
 *
 * 所有关键节点与错误码都会记录在这里，出问题时可以直接把这个页面复制出来排查。
 */
@Composable
fun LogsScreen(vm: HomeViewModel) {

    val logs by vm.logs.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxSize()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    clipboard.setText(AnnotatedString(buildLogText(logs)))
                },
                modifier = Modifier.height(36.dp)
            ) {
                Text("复制全部日志")
            }
            FilledTonalButton(
                onClick = { vm.clearLogs() },
                modifier = Modifier.height(36.dp)
            ) {
                Text("清空")
            }
        }

        if (logs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HintText("暂无日志记录。开始一次压缩后，这里会记录扫描、编码、验证、删除的全过程。")
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        ) {
            items(items = logs, key = { it.id }) { entry ->
                LogRow(entry)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.level) {
        "E" -> MaterialTheme.colorScheme.error
        "W" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = formatClock(entry.timestamp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.level,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            fontSize = 11.sp
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.code,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

private fun buildLogText(logs: List<LogEntry>): String =
    logs.joinToString("\n") { entry ->
        "${formatTime(entry.timestamp)} [${entry.level}] ${entry.code} " +
            "task=${entry.taskId ?: "-"} :: ${entry.message}"
    }
