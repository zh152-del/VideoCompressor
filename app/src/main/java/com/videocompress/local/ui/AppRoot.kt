package com.videocompress.local.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videocompress.local.ui.screens.GuideScreen
import com.videocompress.local.ui.screens.PermissionGate
import com.videocompress.local.ui.screens.HomeScreen
import com.videocompress.local.ui.screens.LogsScreen
import com.videocompress.local.ui.screens.SettingsScreen
import com.videocompress.local.ui.screens.TasksScreen
import kotlinx.coroutines.launch

private enum class Dest(val title: String) {
    HOME("视频压缩"),
    TASKS("任务列表"),
    SETTINGS("设置"),
    LOGS("运行日志"),
    GUIDE("后台运行设置指南")
}

/**
 * 应用根界面：权限门禁 + 底部导航。
 *
 * 没有拿到视频权限时不会进入任何业务界面 —— 拿不到权限就没有可压缩的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: HomeViewModel = viewModel()) {

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(Permissions.allGranted(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = Permissions.allGranted(context)
        if (!granted) {
            scope.launch { snackbarHostState.showSnackbar("没有视频权限就无法读取相册视频") }
        }
    }

    // 从系统设置里授权/撤销后回到前台，重新检查
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = Permissions.allGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val toast by vm.toast.collectAsStateWithLifecycle()
    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeToast()
        }
    }

    if (!granted) {
        PermissionGate(
            onRequest = { permissionLauncher.launch(Permissions.required) },
            snackbarHostState = snackbarHostState
        )
        return
    }

    LaunchedEffect(Unit) { vm.scan() }

    var dest by rememberSaveable { mutableStateOf(Dest.HOME) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(dest.title) },
                navigationIcon = {
                    if (dest == Dest.GUIDE) {
                        IconButton(onClick = { dest = Dest.SETTINGS }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                // 顶栏与整页背景同色，避免出现灰白色带
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            if (dest != Dest.GUIDE) {
                BottomNav(dest) { dest = it }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (dest) {
                Dest.HOME -> HomeScreen(vm) { dest = Dest.GUIDE }
                Dest.TASKS -> TasksScreen(vm)
                Dest.SETTINGS -> SettingsScreen(vm) { dest = Dest.GUIDE }
                Dest.LOGS -> LogsScreen(vm)
                Dest.GUIDE -> GuideScreen()
            }
        }
    }
}

@Composable
private fun BottomNav(current: Dest, onSelect: (Dest) -> Unit) {
    val background = MaterialTheme.colorScheme.background
    NavigationBar(containerColor = background) {
        NavItem(Dest.HOME, current, onSelect, Icons.Default.Compress, "首页")
        NavItem(Dest.TASKS, current, onSelect, Icons.AutoMirrored.Filled.List, "任务")
        NavItem(Dest.SETTINGS, current, onSelect, Icons.Default.Settings, "设置")
        NavItem(Dest.LOGS, current, onSelect, Icons.Default.Article, "日志")
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    dest: Dest,
    current: Dest,
    onSelect: (Dest) -> Unit,
    icon: ImageVector,
    label: String
) {
    val colors = MaterialTheme.colorScheme
    NavigationBarItem(
        selected = current == dest,
        onClick = { onSelect(dest) },
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = colors.primary,
            selectedTextColor = colors.primary,
            indicatorColor = colors.primaryContainer,
            unselectedIconColor = colors.onSurfaceVariant,
            unselectedTextColor = colors.onSurfaceVariant
        )
    )
}
