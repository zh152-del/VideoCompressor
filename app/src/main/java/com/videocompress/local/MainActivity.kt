package com.videocompress.local

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.videocompress.local.ui.AppRoot
import com.videocompress.local.ui.theme.VideoCompressorTheme

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
                AppRoot()
            }
        }
    }
}
