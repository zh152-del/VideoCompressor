package com.videocompress.local.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.videocompress.local.data.TaskStatus
import com.videocompress.local.media.ThumbnailLoader

/**
 * 通用内容卡片。
 *
 * ## 注意：这个名字是历史遗留，实际已是玻璃卡片
 *
 * 早期这里有两套并存的卡片：`FlatCard`（纯描边扁平）和 `GlassVideoCard`（玻璃）。
 * 前者被首页 / 扫描结果 / 设置 / 指南四屏用了 21 处，后者只有任务列表在用，
 * 结果同一个 App 里出现两种完全不同的卡片语言 —— 这就是用户反馈的
 * 「一部分有玻璃效果，一部分没有」。
 *
 * 现在统一到玻璃：保留 `FlatCard` 这个函数名（避免动 21 处调用点），
 * 但内部直接委托给 [GlassCard]，视觉语言与任务列表完全一致。
 * 新代码请直接用 [GlassCard]，需要缩略图时传 `thumbnail` 参数。
 */
@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // 无缩略图、关闭动态背景、较小模糊半径：
    // 这四个屏都是短内容或表单区，不需要逐帧动画，也不需要强模糊。
    GlassCard(
        modifier = modifier,
        thumbnail = null,
        blurRadius = 10.dp,
        animatedBackdrop = false,
        contentPadding = 16.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

/** 分区标题 */
@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

/** 次级说明文字 */
@Composable
fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 18.sp
    )
}

/** 状态标签：只用淡色 tint，不用强色块 */
@Composable
fun StatusChip(status: TaskStatus) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        TaskStatus.PENDING -> scheme.outlineVariant to scheme.onSurfaceVariant
        TaskStatus.INTERRUPTED -> scheme.secondaryContainer to scheme.onSecondaryContainer
        TaskStatus.PROCESSING, TaskStatus.VERIFYING ->
            scheme.primaryContainer to scheme.onPrimaryContainer

        TaskStatus.COMPLETED -> scheme.primaryContainer to scheme.onPrimaryContainer
        TaskStatus.COMPLETED_NO_GAIN -> scheme.secondaryContainer to scheme.onSecondaryContainer
        TaskStatus.COMPLETED_BUT_ORIGINAL_DELETE_FAILED ->
            scheme.secondaryContainer to scheme.onSecondaryContainer

        TaskStatus.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        TaskStatus.CANCELLED -> scheme.outlineVariant to scheme.onSurfaceVariant
        TaskStatus.SKIPPED -> scheme.outlineVariant to scheme.onSurfaceVariant
    }
    ChipSurface(text = status.label, container = container, content = content)
}

/** 通用小标签 */
/**
 * 在 IO 线程加载系统缩略图。
 *
 * 单独抽出来，是因为同一张封面要被用两次：
 * 一次做清晰的小图（用户得能认出是哪条视频），
 * 一次做玻璃卡片的模糊底图（见 [com.videocompress.local.ui.components.GlassVideoCard]）。
 * 共用同一个 remember，避免同一条视频重复读两次 IO。
 *
 * 失败返回 null —— 绝不抛异常、绝不阻塞压缩/扫描/删除。
 */
@Composable
fun rememberVideoThumbnail(uri: Uri): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = null
        withContext(Dispatchers.IO) {
            runCatching { ThumbnailLoader.load(context, uri) }
                .onSuccess { bitmap = it }
                .onFailure { /* 保持 null，显示默认图标 */ }
        }
    }
    return bitmap
}

/**
 * 视频缩略图（清晰小图）。
 *
 * 直接复用系统为相册生成的缩略图，不读取视频正文、不引入额外解码库。
 * 加载在 IO 线程进行，失败只显示默认图标，绝不抛异常、绝不阻塞压缩/扫描/删除。
 */
@Composable
fun VideoThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier,
    sizeDp: Int = 56,
    preloaded: Bitmap? = null
) {
    // 外部（如玻璃卡片）已经加载过就直接用，避免同一条视频重复读两次 IO
    val bitmap = preloaded ?: rememberVideoThumbnail(uri)

    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((sizeDp * 0.5).dp)
            )
        }
    }
}

@Composable
fun ChipSurface(text: String, container: Color, content: Color) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}
