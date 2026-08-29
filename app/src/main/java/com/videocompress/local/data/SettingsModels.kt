package com.videocompress.local.data

/**
 * 压缩质量档位。
 *
 * 第一版只暴露三档，内部映射成「每像素每帧比特数（bpp）」，
 * 再结合分辨率与帧率换算成目标码率，避免让用户面对几十个参数。
 */
enum class QualityPreset(
    val label: String,
    /** bits per pixel per frame */
    val bitsPerPixel: Float,
    val audioBitrate: Int
) {
    HIGH("高质量", 0.090f, 192_000),
    MEDIUM("标准", 0.055f, 128_000),
    LOW("高压缩", 0.035f, 96_000);

    fun describe(): String = when (this) {
        HIGH -> "接近原画质，体积中等下降"
        MEDIUM -> "日常观看几乎无差别，体积明显下降"
        LOW -> "画质有可见损失，体积下降最多"
    }
}

/** 输出分辨率策略：只会下调，绝不放大 */
enum class ResolutionOption(val label: String, val targetHeight: Int) {
    ORIGINAL("原分辨率", 0),
    P1080("1080P", 1080),
    P720("720P", 720),
    P480("480P", 480)
}

/** 队列排序方式 */
enum class SortOrder(val label: String) {
    DATE_DESC("最新优先"),
    DATE_ASC("最旧优先"),
    SIZE_DESC("文件大→小"),
    DURATION_DESC("时长长→短")
}

/** 完整的 App 设置 */
data class AppSettings(
    /** 压缩质量 */
    val quality: QualityPreset = QualityPreset.MEDIUM,
    /** 输出分辨率上限 */
    val resolution: ResolutionOption = ResolutionOption.ORIGINAL,
    /** 压缩并验证成功后是否删除原视频 */
    val deleteOriginal: Boolean = true,
    /** 最低收益阈值：输出必须比原文件小百分之多少才算成功（否则保留原视频） */
    val minSavingsPercent: Int = 5,
    /** 队列排序 */
    val sortOrder: SortOrder = SortOrder.DATE_DESC,
    /** 只对大于该体积（MB）的视频建任务，0 表示全部 */
    val minSizeMb: Int = 10,
    /** HDR / 10bit 视频无法安全处理时直接跳过 */
    val skipHdr: Boolean = true,
    /** 编码格式无法安全确认时跳过 */
    val skipUnsupportedCodec: Boolean = true,
    /** 仅充电时自动继续下一批 */
    val onlyWhenCharging: Boolean = false,
    /** 电量低于该百分比时停止开新任务 */
    val batteryFloorPercent: Int = 15,
    /** 温度过高时停止开新任务 */
    val thermalGuard: Boolean = true,
    /** 每批处理的视频数量（默认 2；同一时刻只编码 1 个） */
    val batchSize: Int = 2
)
