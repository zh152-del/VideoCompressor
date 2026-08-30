package com.videocompress.local.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * 扁平卡片。
 *
 * 卡片容器与整页背景同色，只用一条 1dp 描边做分隔，
 * 避免界面上出现一层层的灰白色块。
 */
@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
 * 视频缩略图。
 *
 * 直接复用系统为相册生成的缩略图，不读取视频正文、不引入额外解码库。
 * 加载在 IO 线程进行，失败只显示默认图标，绝不抛异常、绝不阻塞压缩/扫描/删除。
 */
@Composable
fun VideoThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier,
    sizeDp: Int = 56
) {
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
