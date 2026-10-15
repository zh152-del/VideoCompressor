package com.videocompress.local

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.videocompress.local.ui.AppRoot
import com.videocompress.local.ui.theme.VideoCompressorTheme
import com.videocompress.local.service.CompressionController

/**
 * 唯一入口 Activity。
 *
 * 压缩逻辑全部在前台服务里，Activity 被销毁也不会影响压缩，
 * 重新打开时可以完整恢复进度与状态。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VideoCompressorTheme {
                // 「息屏就死」的另一半解药：Activity 在前台时保持屏幕点亮。
                //
                // 为什么必须在 Activity 做（服务里 addView 那种写法行不通）：
                //  · TYPE_PHONE / TYPE_APPLICATION_OVERLAY 窗口在 Android 10+ 需要
                //    SYSTEM_ALERT_WINDOW 权限，前台服务拿不到（实测 BadTokenException）
                //  · View 必须在有 Looper 的线程创建，服务跑在 IO 线程上
                //    （实测 Can't create handler inside thread）
                // 而 window.decorView.keepScreenOn(true) 是系统提供的能力，不需要任何权限。
                //
                // 覆盖范围说明（重要，不夸大）：
                //  · 用户停留在 App 里时 → 屏幕一定不会熄灭，压缩稳定
                //  · 用户切走或锁屏 → 由服务的 PARTIAL_WAKE_LOCK 保 CPU 继续编码，
                //    但部分 ROM 的合成器会停摆，此时仍可能卡住
                // 后台那一半的根治需要厂商白名单，App 侧无法强求，
                // 所以「后台运行设置指南」那页依然必要。
                val running by CompressionController.running.collectAsState()
                DisposableEffect(running) {
                    // setKeepScreenOn 是 View 的方法，要挂在 decorView 上
                    val decor = window.decorView
                    decor.keepScreenOn = running
                    onDispose { decor.keepScreenOn = false }
                }
                AppRoot()
            }
        }
    }
}