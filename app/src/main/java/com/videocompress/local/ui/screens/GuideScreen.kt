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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videocompress.local.ui.components.FlatCard
import com.videocompress.local.ui.components.HintText
import com.videocompress.local.ui.components.SectionTitle

/**
 * 后台运行设置指南（VIVO / OriginOS）。
 *
 * 只提供官方设置路径指引，不要求 Root、无障碍、悬浮窗，
 * 也不使用定时拉活、无限唤醒这类恶意保活手段。
 */
@Composable
fun GuideScreen() {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        FlatCard {
            SectionTitle("先说清楚")
            HintText(
                "本应用使用 Android 官方的「媒体处理前台服务」在后台压缩视频，" +
                    "支持锁屏后继续运行。但 Android 系统以及 VIVO 的省电策略都有可能中断任务，"
            )
            HintText(
                "任何中断都不会丢失你的视频：任务状态实时保存在本地数据库，" +
                    "重新打开 App 点「开始压缩」即可接着处理，不会重复压缩、更不会误删原视频。"
            )
        }

        FlatCard {
            SectionTitle("VIVO / OriginOS 建议设置")
            GuideStep(
                index = 1,
                title = "允许自启动",
                detail = "设置 → 应用与权限 → 应用管理 → 本应用 → 权限 → 自启动：开启"
            )
            GuideStep(
                index = 2,
                title = "关闭电池优化",
                detail = "设置 → 电池 → 后台耗电管理 / 应用耗电管理 → 本应用 → 允许后台高耗电"
            )
            GuideStep(
                index = 3,
                title = "允许后台运行",
                detail = "设置 → 电池 → 后台运行管理 → 本应用 → 允许后台运行"
            )
            GuideStep(
                index = 4,
                title = "锁定最近任务",
                detail = "多任务界面 → 长按本应用卡片 → 锁定。避免被一键清理"
            )
            GuideStep(
                index = 5,
                title = "压缩时不要开启极致省电",
                detail = "设置 → 电池 → 省电模式 / 极致省电：处理大批量视频时建议关闭"
            )
            GuideStep(
                index = 6,
                title = "允许通知",
                detail = "设置 → 通知与状态栏 → 本应用 → 允许通知。否则看不到压缩进度"
            )
        }

        FlatCard {
            SectionTitle("其它品牌（通用思路）")
            GuideStep(index = 1, title = "把应用加入电池优化白名单", detail = "系统设置里搜索「电池优化」")
            GuideStep(index = 2, title = "允许自启动 / 关联启动", detail = "系统设置里搜索「自启动」")
            GuideStep(index = 3, title = "锁定最近任务卡片", detail = "多任务界面长按卡片锁定")
        }

        FlatCard {
            SectionTitle("遇到任务中断怎么办")
            HintText(
                "1. 重新打开 App，任务列表里会显示「已中断」或「待处理」，说明状态已安全保存。"
            )
            HintText(
                "2. 点「开始压缩」即可继续，已完成的任务不会重复处理。"
            )
            HintText(
                "3. 如果频繁中断，优先检查上面的电池优化与自启动设置，并把「仅充电时继续」打开。"
            )
            HintText(
                "4. 长时间大批量压缩建议连接充电器，并留意手机温度：温度过高时应用会主动停止新任务保护硬件。"
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GuideStep(index: Int, title: String, detail: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            HintText(detail)
        }
    }
}
