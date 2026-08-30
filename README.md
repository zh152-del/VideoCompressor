# 本地视频压缩器（Android）

完全本地运行的 Android 视频压缩 App。不联网、不上传、不需要登录账号，所有处理都在手机本地完成。

- 目标设备：兼容主流 Android 版本
- 最低支持：Android 8.0（API 26）
- 目标版本：Android 16（API 36）
- 不使用 Root、无障碍、悬浮窗、服务器、远程数据库

---

## 一、技术选型

| 项目 | 选择 | 说明 |
| --- | --- | --- |
| 语言 | Kotlin 2.0.21 | |
| UI | Jetpack Compose + Material3 | |
| 视频源 | Android MediaStore | 不硬编码 `/DCIM`、`/Movies` 目录 |
| 压缩引擎 | **Media3 Transformer 1.5.1** | 系统硬件编码器，**未引入 FFmpeg** |
| 任务队列 | Room 2.7.0 | 状态持久化，App 被杀 / 重启都能恢复 |
| 设置存储 | DataStore | |
| 后台 | Foreground Service（`mediaProcessing`） | Android 15+ 官方媒体处理类型 |

### 为什么没有用 FFmpeg

需求里列出的核心能力（重编码、降码率、调分辨率、H.264 输出、AAC 音频、保留音频、保留方向、保留基础元数据）Media3 Transformer 全部满足，因此不引入 FFmpeg，规避了：

- APK 体积膨胀（arm64-v8a + armeabi-v7a 双 ABI 原生库）
- native library 崩溃
- GPL / LGPL 许可证问题
- 硬件编码兼容性与不同 VIVO 机型差异

输出统一为 **H.264 + AAC / MP4**，任何设备都能播放。

---

## 二、核心设计

### 1. 每批 2 个，但同时只编码 1 个

```
批次 = [视频1, 视频2]  →  视频1 编码完成  →  视频2 编码完成  →  下一批
```

`maxConcurrentTranscodes = 1`，`batchSize` 可在设置里调成 2 / 3 / 4。
不做并发编码，避免 CPU 暴涨、GPU/硬件编码资源竞争、升温、耗电、内存压力、编码器初始化失败，以及更容易触发 VIVO 的后台限制。

### 2. 安全删除原视频（最重要的机制）

```
原视频
  ↓
创建临时输出 temp_xxx.mp4
  ↓
开始压缩
  ↓
压缩完成
  ↓
验证临时文件（大小 / 视频轨 / 时长 / 分辨率 / 方向 / 比例 / 音轨 / 收益）
  ↓
写入 MediaStore（IS_PENDING 机制）
  ↓
再次验证最终文件
  ↓
先落库标记 COMPLETED      ← 关键：绝不允许「原视频已删但状态还是 PENDING」
  ↓
最后删除原视频
```

任何一步失败：**保留原视频**。

### 3. 状态机

```
PENDING ──► PROCESSING ──► VERIFYING ──► COMPLETED ──► (删除原视频)
INTERRUPTED ─► PENDING          （App 被杀 / 重启后恢复）

PROCESSING ──► FAILED     压缩失败，原视频保留
PROCESSING ──► CANCELLED  用户取消，原视频保留
PENDING    ──► SKIPPED    不安全（HDR / 无视频轨 / 编码不支持），原视频保留
VERIFYING  ──► COMPLETED_NO_GAIN                   压缩成功但没变小，保留原视频
COMPLETED  ──► COMPLETED_BUT_ORIGINAL_DELETE_FAILED 压缩成功但删除原视频失败
```

删除失败不算任务失败，也不会重新压缩，UI 会提示「压缩成功，但删除原视频失败」，可以在任务页通过系统弹窗确认删除。

### 4. 输出文件验证（OutputVerifier）

压缩结果必须通过全部校验才会替换原视频：

1. 文件存在且 > 4KB
2. Android 媒体框架可以解析
3. 存在视频轨、时长 > 0
4. 时长与源差异在容差内（1.5s 或 5%）
5. 画面方向（横/竖）与源一致
6. 画面比例偏差 < 8%
7. 源有音轨时，输出必须有音轨（防止「压缩完没声音」）
8. 输出体积必须比原文件小（阈值可在设置里调）

### 5. 避免重复压缩

以 **MediaStore Uri + 数据库任务记录** 作为判断依据，主 Uri 建唯一索引。
不使用 `filename.contains("compressed")` 这种不可靠的判断。
已处理的视频直接跳过；输出文件名冲突时自动追加 `_1`、`_2`，绝不覆盖用户已有视频。

### 6. 后台与中断

- 使用 `mediaProcessing` 类型的 Foreground Service，只在用户点击「开始压缩」时启动，**绝不偷偷自启**
- `START_NOT_STICKY`：被系统杀掉后不自动重启，任务状态已落库，用户重新打开点继续即可
- Android 15+ 的 6 小时额度耗尽（`onTimeout`）时：当前任务恢复为 `INTERRUPTED`，**绝不标记 COMPLETED，绝不删原视频**
- 批与批之间检查电量、充电状态、温度、剩余存储，越界就停止开新任务（当前视频会正常跑完）

### 7. 保护策略

- **存储**：需求空间 = 预估输出 × 2（临时文件 + 最终文件）+ 512MB 余量，不足直接停止
- **电量**：低于设定值（默认 15%）停止开新任务，充电时不受限
- **温度**：`THERMAL_STATUS_SEVERE` 及以上停止开新任务
- **HDR / 10bit**：默认跳过，不强行转换
- **收益**：输出没有明显变小就保留原视频

### 8. 内存与线程

- 全程流式处理（Media3 Transformer / MediaCodec），绝不把整个视频读进 `ByteArray`
- 扫描、压缩、数据库读写全部在 IO 线程，UI 线程只负责显示状态、发送命令、接收进度
- 通知进度做节流（变化 ≥ 2% 且间隔 ≥ 800ms 才刷新），避免频繁 IPC 耗电

---

## 三、目录结构

```
app/src/main/java/com/videocompress/local/
├── App.kt                        Application，冷启动恢复
├── MainActivity.kt               唯一入口 Activity
├── data/
│   ├── TaskStatus.kt             状态机定义
│   ├── VideoTask.kt              任务实体
│   ├── TaskDao.kt / AppDatabase.kt / TaskRepository.kt
│   ├── LogEntry.kt / LogDao.kt   日志
│   ├── SettingsModels.kt / SettingsRepository.kt
├── media/
│   ├── MediaStoreScanner.kt      相册扫描
│   ├── VideoProbe.kt             元数据探测（旋转 / HDR / 编码 / 音轨 / 帧率）
│   ├── CompressionPlan.kt        码率与分辨率规划
│   ├── CompressionEngine.kt      Media3 Transformer 封装
│   ├── OutputVerifier.kt         输出验证
│   ├── MediaStorePublisher.kt    发布输出 / 删除原视频
│   └── DeviceGuard.kt            存储 / 电量 / 温度守卫
├── service/
│   ├── CompressionService.kt     前台服务与批次队列
│   └── NotificationHelper.kt     通知
├── ui/
│   ├── AppRoot.kt / HomeViewModel.kt / Permissions.kt
│   ├── theme/Theme.kt
│   ├── components/Common.kt
│   └── screens/                  首页 / 任务 / 设置 / 日志 / 后台指南
└── util/
    ├── AppLog.kt                 日志系统（Room + 文件）
    └── FormatUtils.kt
```

---

## 四、权限

只申请真正需要的：

| 权限 | 说明 |
| --- | --- |
| `READ_MEDIA_VIDEO` | Android 13+ 读取视频 |
| `READ_EXTERNAL_STORAGE`（maxSdk 32） | 旧版本读取视频 |
| `WRITE_EXTERNAL_STORAGE`（maxSdk 28） | Android 9 及以下写回相册 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROCESSING` | 后台压缩 |
| `POST_NOTIFICATIONS` | 显示压缩进度 |
| `WAKE_LOCK` | 息屏时保持 CPU 完成当前编码 |

**绝不申请**：通讯录、电话、短信、定位、麦克风、摄像头、无障碍、悬浮窗、VPN。

---

## 五、编译

### 环境

- JDK 17+（仓库里没有自带 JDK，使用 Android Studio 自带的即可）
- Android SDK（compileSdk 36，build-tools 36.0.0）

### 命令

```bash
# Debug
./gradlew assembleDebug

# Release（已配置签名，输出在 app/build/outputs/apk/release/）
./gradlew assembleRelease
```

Windows 下也可以直接双击项目根目录的 `构建APK.cmd`。

签名信息（自签名，仅用于本机覆盖安装）：

```
keystore : app/keystore/videocompress.jks
alias    : videocompress
password : videocompress
```

---

## 六、已知边界

- **HDR / Dolby Vision / 10bit** 视频默认跳过（无法保证安全转换），不会删除原视频
- 第一版优先保证普通 H.264 SDR 视频
- 分辨率下调通过 Media3 的 `Presentation` 实现，只缩小不放大
- 进度来自 `Transformer.getProgress()`，是真实编码进度；编码器缓冲阶段会短暂停留在上一次数值，不会假报
- Android 10+ 删除「不是本应用创建」的媒体需要系统弹窗确认，此时任务会停在「待删除原视频」，需要用户在任务页确认

---

## 七、日志

- App 内「日志」页可查看全部关键节点，支持一键复制
- 同时写入 `/data/data/com.videocompress.local/files/logs/compress-YYYY-MM-DD.log`
- 记录的节点：`SCAN_START`、`FOUND_VIDEO_COUNT`、`TASK_START`、`ENCODE_COMPLETE`、`VERIFY_SUCCESS`、`DELETE_ORIGINAL_SUCCESS`、`TASK_COMPLETE`，以及所有 `ERROR_CODE` + `ERROR_MESSAGE`
