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
    /**
     * bpp 数值参考：
     *  · 1080p30 @ 0.055bpp ≈ 3.4 Mbps（日常观看几乎无差别）
     *  · 1080p30 @ 0.090bpp ≈ 5.6 Mbps
     *
     * 早期版本高质量档用 0.090bpp，1080p30 会算出 18.6Mbps 的目标码率 ——
     * 比很多原视频（尤其是屏幕录制、老旧 720P）本身还高，于是压完反而更大
     * （真机上被实测到「原 10654KB → 输出 47488KB」）。
     * 现在三个档位收紧到 0.055 / 0.040 / 0.028，高质量与标准的画质差距靠
     * 码率体现，而不是靠"给一个必然导致变大的荒谬上限"。
     */
    HIGH("高质量", 0.055f, 192_000),
    MEDIUM("标准", 0.040f, 128_000),
    LOW("高压缩", 0.028f, 96_000);

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
    /**
     * 最低收益阈值：输出必须比原文件小百分之多少才算成功（否则保留原视频）。
     *
     * 默认 30 而不是以前的 5 —— 5% 的门槛等于"只要小一点点就算成功"，
     * 用户花几分钟编码、耗光电，最后只省下 5%，得不偿失。
     * 30% 是一个"用户能明确感知到值"的下限。
     */
    val minSavingsPercent: Int = 30,
    /** 队列排序 */
    val sortOrder: SortOrder = SortOrder.DATE_DESC,
    /**
     * 只对大于该体积（MB）的视频建任务，0 表示全部。
     *
     * 默认 0（全部入队）：以前默认 10MB，把大量小视频提前挡在队列外，
     * 但小视频同样可能有可观的压缩空间，提前拍死等于替用户做了决定。
     * 真正的"该不该压"由码率与收益规则判定，比体积阈值准确得多。
     */
    val minSizeMb: Int = 0,
    /**
     * HDR / 10bit 视频无法安全处理时直接跳过。
     *
     * 默认 false（不跳过）：HDR 在 vivo / OriginOS 上占比不低，
     * 默认跳过等于让这批视频完全无法处理，用户会以为 App「不支持我的视频」。
     * 改成"尝试压缩并按体积验证"，失败也只是保留原视频，不会有数据风险。
     */
    val skipHdr: Boolean = false,
    /** 编码格式无法安全确认时跳过（这一项建议保持开启：非标准编码强行转码风险高） */
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
