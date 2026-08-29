package com.videocompress.local.data

/**
 * 视频压缩任务的状态机。
 *
 * 正常流程：
 *   PENDING ──► PROCESSING ──► VERIFYING ──► COMPLETED ──► (删除原视频)
 *   INTERRUPTED ──► PENDING（App 被杀 / 手机重启后恢复）
 *
 * 异常流程：
 *   PROCESSING ──► FAILED      ：压缩失败，原视频一定保留
 *   PROCESSING ──► CANCELLED   ：用户取消，原视频一定保留
 *   PENDING    ──► SKIPPED     ：无法安全处理（HDR / 无视频轨 / 编码不支持），原视频保留
 *   VERIFYING  ──► COMPLETED_NO_GAIN                  ：压缩成功但没变小，保留原视频
 *   COMPLETED  ──► COMPLETED_BUT_ORIGINAL_DELETE_FAILED：压缩成功但删除原视频失败
 *
 * 安全原则：除了 COMPLETED，任何状态下都不会删除原视频。
 */
enum class TaskStatus(val label: String) {

    /** 等待压缩 */
    PENDING("待处理"),

    /** 被系统或异常中断，等待用户恢复（不会被当成未完成而重新排队，需用户确认） */
    INTERRUPTED("已中断"),

    /** 正在编码 */
    PROCESSING("压缩中"),

    /** 编码完成，正在验证输出文件 */
    VERIFYING("验证中"),

    /** 完成，原视频已按设置处理 */
    COMPLETED("已完成"),

    /** 压缩成功但输出没有变小，已保留原视频并丢弃压缩结果 */
    COMPLETED_NO_GAIN("无收益·已保留原视频"),

    /** 压缩成功，但系统不允许直接删除原视频（需要用户确认） */
    COMPLETED_BUT_ORIGINAL_DELETE_FAILED("待删除原视频"),

    /** 失败，原视频保留 */
    FAILED("失败"),

    /** 用户取消，原视频保留 */
    CANCELLED("已取消"),

    /** 不安全，主动跳过，原视频保留 */
    SKIPPED("已跳过");

    /** 是否处于「等待处理」状态（可以被调度） */
    val isWaiting: Boolean
        get() = this == PENDING || this == INTERRUPTED

    /** 是否处于「正在处理」状态（App 被杀后需要恢复） */
    val isBusy: Boolean
        get() = this == PROCESSING || this == VERIFYING

    /** 是否已经终态 */
    val isTerminal: Boolean
        get() = this == COMPLETED ||
            this == COMPLETED_NO_GAIN ||
            this == COMPLETED_BUT_ORIGINAL_DELETE_FAILED ||
            this == FAILED ||
            this == CANCELLED ||
            this == SKIPPED

    /** 是否代表「压缩这一动作本身成功」 */
    val isCompressionSucceeded: Boolean
        get() = this == COMPLETED ||
            this == COMPLETED_NO_GAIN ||
            this == COMPLETED_BUT_ORIGINAL_DELETE_FAILED

    companion object {
        /** 数据库里可能出现的等待态字符串集合（SQL 用） */
        const val WAITING_SQL = "('PENDING','INTERRUPTED')"
        const val BUSY_SQL = "('PROCESSING','VERIFYING')"
        const val DONE_SQL =
            "('COMPLETED','COMPLETED_NO_GAIN','COMPLETED_BUT_ORIGINAL_DELETE_FAILED')"
    }
}
