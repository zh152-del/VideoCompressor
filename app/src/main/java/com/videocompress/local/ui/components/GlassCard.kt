package com.videocompress.local.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 卡片圆角，玻璃层与边缘光共用，保证描边和裁剪严格贴合。 */
private val GlassCornerRadius = 18.dp

/**
 * 通用玻璃卡片 —— 全 App 唯一的卡片样式。
 *
 * ## 为什么只有这一种
 *
 * 早期版本里同时存在两套卡片：`FlatCard`（纯描边扁平）和 `GlassVideoCard`
 * （玻璃）。前者被首页 / 扫描结果 / 设置 / 指南四屏用了 21 处，后者只有
 * 任务列表在用，结果同一个 App 里出现两种完全不同的卡片语言，
 * 看起来像两个 App 拼在一起。
 *
 * 现在统一到玻璃：所有卡片都由这个组件合成，视觉语言一致。
 * 「要不要放缩略图」交给 [thumbnail] 参数决定，而不是决定用哪个组件。
 *
 * ## 合成层次（与 [GlassVideoCard] 完全一致）
 *
 * ```
 *   ① 视频封面 / 视频内容（[thumbnail] 为 null 时用主题底色）
 *   ② 动态背景（可选，见 [animatedBackdrop]）
 *   ③ Blur            ← ①② 打包后统一模糊
 *   ④ Glass Material  ← 半透明渐变 + 压暗 scrim
 *   ⑤ 高光 / 边缘光    ← 左上亮、右下弱的渐变描边
 *   ⑥ UI 内容          ← 由 content 提供
 * ```
 *
 * 两个关键取舍：
 *
 * 1. **①② 一起送进 ③ 模糊**：动态背景如果画在玻璃"上面"会变成一层脏浮色；
 *    把光斑和封面一起模糊，流动感才是"透过玻璃渗出来"的。
 * 2. **模糊前先 `scale(1.08f)`**：模糊会采样到边缘之外的透明像素，
 *    直接模糊会在四周留一圈发灰的边。轻微放大再裁剪可彻底消除。
 *
 * @param thumbnail 视频封面位图；null 时用主题底色兜底，玻璃依然有内容可"糊"
 * @param blurRadius 模糊半径。**长列表（几百条）建议调小或设为 0.dp**，
 *        否则逐行模糊会明显掉帧
 * @param animatedBackdrop 是否开启动态背景。长列表务必 false
 * @param glassAlpha 玻璃层的白色强度
 * @param scrimAlpha 压暗强度，保证 UI 内容在明亮画面上依然可读
 * @param rimLightAlpha 边缘光强度
 * @param contentPadding 内容内边距；传 null 可自行控制
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    thumbnail: Bitmap? = null,
    blurRadius: Dp = 18.dp,
    animatedBackdrop: Boolean = false,
    glassAlpha: Float = 0.16f,
    scrimAlpha: Float = 0.38f,
    rimLightAlpha: Float = 0.60f,
    contentPadding: Dp? = 16.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(GlassCornerRadius)

    // 明/暗两套配方。目标是让"卡片表面"始终与 MaterialTheme 的文字色匹配：
    //   · 亮色主题 → 磨砂偏白，表面的 onSurface（深色文字）依然可读
    //   · 暗色主题 → 磨砂偏暗，表面的 onSurface（浅色文字）依然可读
    // 好处：卡片里的 Text/Chip 一个颜色都不用改，直接沿用 MaterialTheme 即可。
    val glassTop: Color
    val glassBottom: Color
    val scrim: Color
    if (dark) {
        glassTop = Color.White.copy(alpha = glassAlpha)
        glassBottom = Color.White.copy(alpha = glassAlpha * 0.35f)
        scrim = Color.Black.copy(alpha = scrimAlpha)
    } else {
        // 亮色主题下大幅提高白色占比，把彩色封面"洗"成磨砂白，
        // 只留一点点色彩透出来 —— 这才是玻璃，而不是一块彩色图片。
        glassTop = Color.White.copy(alpha = (glassAlpha * 3.9f).coerceAtMost(0.92f))
        glassBottom = Color.White.copy(alpha = (glassAlpha * 3.0f).coerceAtMost(0.88f))
        scrim = Color.Black.copy(alpha = scrimAlpha * 0.16f)
    }

    // ------------------------------------------------------------------
    // 布局策略：内容优先，装饰跟随内容
    //
    // 之前所有装饰层都用 matchParentSize()，要求调用方给出确定高度。
    // 但实际调用点（LazyColumn item、Column 里的 FlatCard）常常只给
    // fillMaxWidth，高度是无界的 —— 此时 matchParentSize() 拿到 0 高度，
    // 整张卡片塌掉。真机上曾表现为：批量操作条只剩一条缝，
    // 下方任务列表整片消失（LazyColumn 被 0 高度的兄弟节点挤没）。
    //
    // 现在改成：先把内容画进一个"测量容器"，装饰层用 wrapContentSize + 偏移
    // 跟随内容尺寸。这样无论调用方给不给高度，卡片都恰好包住内容。
    // ------------------------------------------------------------------
    Box(
        modifier = modifier
            .wrapContentSize()
            .clip(shape)
    ) {

        // ① + ② + ③：底图（缩略图 / 动态背景）统一模糊
        Box(
            modifier = Modifier
                .matchParentSize()
                .scale(1.08f)
                .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
        ) {
            // ① 视频封面 / 视频内容
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            // ② 动态背景
            if (animatedBackdrop) {
                GlassAnimatedBackdrop()
            }
        }

        // ================= ④ Glass Material Layer =================
        // 半透明渐变：上半亮、下半淡，模拟真实玻璃的厚度感
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(glassTop, glassBottom)))
        )
        // 压暗层：保证上层 UI 内容在任何画面上都满足可读性
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(scrim)
        )

        // ================= ⑤ 高光 / 边缘光 =================
        // 一圈渐变描边：左上最亮 → 右下几乎消失，模拟玻璃边缘的折射高光
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val strokeWidth = 1.5.dp.toPx()
                    val radiusPx = GlassCornerRadius.toPx()
                    val bright = Color.White.copy(alpha = rimLightAlpha)
                    val dim = Color.White.copy(alpha = rimLightAlpha * 0.06f)

                    onDrawBehind {
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    bright,
                                    dim,
                                    bright.copy(alpha = rimLightAlpha * 0.30f)
                                ),
                                start = Offset.Zero,
                                end = Offset(size.width, size.height)
                            ),
                            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                            size = Size(size.width - strokeWidth, size.height - strokeWidth),
                            cornerRadius = CornerRadius(radiusPx, radiusPx),
                            style = Stroke(strokeWidth)
                        )
                    }
                }
        )

        // ================= ⑥ UI 内容 =================
        // 用 fillMaxWidth + wrapContentHeight：宽度撑满，高度由内容决定。
        // 这样上层的 matchParentSize() 才能拿到真实高度去铺玻璃层。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .then(
                    if (contentPadding != null) Modifier.padding(contentPadding)
                    else Modifier
                ),
            content = content
        )
    }
}

/**
 * 动态背景：两团缓慢漂移的彩色光斑。
 *
 * 只做位移、不重绘布局，代价是一次 `drawRect` 渐变；
 * 配合 [GlassCard] 的模糊，就是"流动的高级感"。
 */
@Composable
private fun GlassAnimatedBackdrop() {
    val transition = rememberInfiniteTransition(label = "glassBackdrop")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glassBackdropShift"
    )

    // 主题青 + 冷蓝，与 App 主色（#00695C / #4DD0BE）同族
    val accentA = Color(0xFF4DD0BE)
    val accentB = Color(0xFF5B8CFF)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height

                onDrawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(accentA.copy(alpha = 0.50f), Color.Transparent),
                            center = Offset(
                                x = w * (0.15f + 0.22f * shift),
                                y = h * (0.18f + 0.20f * shift)
                            ),
                            radius = w * 0.90f
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(accentB.copy(alpha = 0.42f), Color.Transparent),
                            center = Offset(
                                x = w * (0.85f - 0.22f * shift),
                                y = h * (0.82f - 0.20f * shift)
                            ),
                            radius = w * 0.95f
                        )
                    )
                }
            }
    )
}