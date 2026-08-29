package com.videocompress.local.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 权限设计：只申请视频处理真正需要的权限。
 *
 * 绝对不申请：通讯录、电话、短信、定位、麦克风、摄像头、无障碍、悬浮窗、VPN。
 */
object Permissions {

    /** Android 13+ 用 READ_MEDIA_VIDEO，旧版本用 READ_EXTERNAL_STORAGE */
    val mediaPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** 前台服务的通知需要这个权限（Android 13+） */
    private val notificationPermission: String
        get() = Manifest.permission.POST_NOTIFICATIONS

    /** 需要用户授予的全部权限 */
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(mediaPermission, notificationPermission)
        } else {
            arrayOf(mediaPermission)
        }

    fun hasMediaPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, mediaPermission) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, notificationPermission) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun allGranted(context: Context): Boolean =
        hasMediaPermission(context) && hasNotificationPermission(context)
}
