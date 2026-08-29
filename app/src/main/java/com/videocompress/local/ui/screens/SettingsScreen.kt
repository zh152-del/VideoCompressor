package com.videocompress.local.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videocompress.local.BuildConfig
import com.videocompress.local.data.AppSettings
import com.videocompress.local.data.QualityPreset
import com.videocompress.local.data.ResolutionOption
import com.videocompress.local.data.SortOrder
import com.videocompress.local.ui.HomeViewModel
import com.videocompress.local.ui.components.FlatCard
import com.videocompress.local.ui.components.HintText
import com.videocompress.local.ui.components.SectionTitle

/**
 * 设置页。
 *
 * 第一版只暴露必要的几个参数，不做「几十个高级选项」。
 * 所有修改立即写入 DataStore，排序类修改会立刻重排队列。
 */
@Composable
fun SettingsScreen(vm: HomeViewModel, onOpenGuide: () -> Unit) {

    val settings by vm.settings.collectAsStateWithLifecycle()
    var sortDialogOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // ------------------------------------------------------------ 质量
        FlatCard {
            SectionTitle("压缩质量")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityPreset.entries.forEach { preset ->
                    OptionChip(
                        label = preset.label,
                        selected = settings.quality == preset,
                        onClick = { vm.updateSettings { it.copy(quality = preset) } }
                    )
                }
            }
            HintText(settings.quality.describe())
        }

        // ------------------------------------------------------------ 分辨率
        FlatCard {
            SectionTitle("输出分辨率")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResolutionOption.entries.forEach { option ->
                    OptionChip(
                        label = option.label,
                        selected = settings.resolution == option,
                        onClick = { vm.updateSettings { it.copy(resolution = option) } }
                    )
                }
            }
            HintText("只会下调分辨率，绝不把小视频放大：4K→1080P，1080P→1080P，720P→720P。")
        }

        // ------------------------------------------------------------ 原视频处理
        FlatCard {
            SectionTitle("原视频处理")
            SwitchRow(
                title = "压缩成功后删除原视频",
                subtitle = "只有在输出文件通过完整校验之后才会执行，任何失败都会保留原视频",
                checked = settings.deleteOriginal,
                onCheckedChange = { value -> vm.updateSettings { it.copy(deleteOriginal = value) } }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SliderRow(
                title = "最低收益阈值",
                valueLabel = "${settings.minSavingsPercent}%",
                value = settings.minSavingsPercent.toFloat(),
                range = 0f..50f,
                steps = 9,
                subtitle = "输出必须比原文件小这么多才算成功，否则保留原视频并丢弃压缩结果",
                onValueChange = { value ->
                    vm.updateSettings { it.copy(minSavingsPercent = value.toInt()) }
                }
            )
        }

        // ------------------------------------------------------------ 队列
        FlatCard {
            SectionTitle("队列")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "排序方式",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { sortDialogOpen = true }) {
                    Text(settings.sortOrder.label)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SliderRow(
                title = "最小文件大小",
                valueLabel = if (settings.minSizeMb > 0) "${settings.minSizeMb} MB" else "全部",
                value = settings.minSizeMb.toFloat(),
                range = 0f..500f,
                steps = 50,
                subtitle = "只对大于该体积的视频建立任务，0 表示全部视频",
                onValueChange = { value -> vm.updateSettings { it.copy(minSizeMb = value.toInt()) } }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "每批处理数量",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2, 3, 4).forEach { size ->
                        OptionChip(
                            label = size.toString(),
                            selected = settings.batchSize == size,
                            onClick = { vm.updateSettings { it.copy(batchSize = size) } }
                        )
                    }
                }
            }
            HintText("同一时刻只会编码 1 个视频，避免 CPU/GPU 资源竞争导致升温、耗电与编码器初始化失败。")
        }

        // ------------------------------------------------------------ 安全
        FlatCard {
            SectionTitle("安全策略")
            SwitchRow(
                title = "跳过 HDR / 10bit 视频",
                subtitle = "这类视频无法保证安全转换，直接跳过并保留原视频",
                checked = settings.skipHdr,
                onCheckedChange = { value -> vm.updateSettings { it.copy(skipHdr = value) } }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(
                title = "跳过不支持的编码格式",
                subtitle = "仅处理 H.264 / H.265 / MPEG4 / VP8 / VP9 / AV1 等常见编码",
                checked = settings.skipUnsupportedCodec,
                onCheckedChange = { value ->
                    vm.updateSettings { it.copy(skipUnsupportedCodec = value) }
                }
            )
        }

        // ------------------------------------------------------------ 后台与保护
        FlatCard {
            SectionTitle("后台与设备保护")
            SwitchRow(
                title = "仅充电时继续下一批",
                checked = settings.onlyWhenCharging,
                onCheckedChange = { value -> vm.updateSettings { it.copy(onlyWhenCharging = value) } }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SliderRow(
                title = "电量下限",
                valueLabel = "${settings.batteryFloorPercent}%",
                value = settings.batteryFloorPercent.toFloat(),
                range = 5f..50f,
                steps = 14,
                subtitle = "电量低于该值时停止开启新任务（充电时不受限制）",
                onValueChange = { value ->
                    vm.updateSettings { it.copy(batteryFloorPercent = value.toInt()) }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(
                title = "温度保护",
                subtitle = "手机温度过高时，完成当前视频后停止新任务",
                checked = settings.thermalGuard,
                onCheckedChange = { value -> vm.updateSettings { it.copy(thermalGuard = value) } }
            )
        }

        // ------------------------------------------------------------ 关于
        FlatCard {
            SectionTitle("关于")
            HintText(
                "全部处理都在手机本地完成：不联网、不上传、不需要登录账号、不使用服务器与远程数据库。"
            )
            HintText(
                "压缩引擎：Android Media3 Transformer（系统硬件编码器），未引入 FFmpeg，避免体积与许可证问题。"
            )
            HintText("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            FilledTonalButton(onClick = onOpenGuide, modifier = Modifier.height(36.dp)) {
                Text("后台运行设置指南", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (sortDialogOpen) {
        AlertDialog(
            onDismissRequest = { sortDialogOpen = false },
            title = { Text("排序方式") },
            text = {
                Column {
                    SortOrder.entries.forEach { order ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.sortOrder == order,
                                onClick = {
                                    vm.updateSettings { it.copy(sortOrder = order) }
                                    sortDialogOpen = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(order.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sortDialogOpen = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

/** 单行开关 */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                HintText(it)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 带数值显示的滑块 */
@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
        subtitle?.let { HintText(it) }
    }
}
