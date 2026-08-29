# 本地视频压缩 App — 系统级 Bug 审计报告

> 项目：VideoCompressor（Kotlin + Jetpack Compose + Media3 Transformer 1.5.1 + Room + DataStore）
> 审计依据：`文本2.txt`（全面 Bug 审计 + 修复任务）
> 审计方式：全量静态代码审计（36 个 Kotlin 源文件 + Manifest/Gradle/资源，约 5044 行）+ 编译验证
> 构建工具：Gradle 8.11.1（managed）、JAVA_HOME = `E:\Android\jbr`、compileSdk/targetSdk 36、minSdk 26

---

## ⚠️ 环境与验证边界（必须如实说明）

本环境**没有真实 Android 设备，也没有模拟器**，因此：

- **无法执行** spec 要求的「暴力测试 / 压力测试（10/50/100/500 视频）/ VIVO·OriginOS 真机后台测试 / 视频兼容性矩阵 A–G 实机编码 / 真机 Logcat 验证」。
- 本次交付的「测试」= **静态代码审计 + 编译验证（Debug / Release 均 BUILD SUCCESSFUL）+ 状态机/数据流推演**。
- 所有标注 **「需真机验证」** 的项目，均无法仅凭静态分析下结论，已在下方明确列出，未伪造测试结果（遵守 spec §24/§25）。

---

## 一、审计覆盖的分类（对应 spec 一~二十五）

| 分类 | 结论 |
|---|---|
| P0 数据安全（原视频误删） | 删除前双重校验（temp 校验 + 最终 Uri 校验）+ 最保守判断，已闭合 |
| P1 核心功能（编码/方向/比例） | 旋转映射重写，已闭合 |
| P1 任务系统（状态机/并发/恢复） | PROCESSING/VERIFYING → INTERRUPTED 全路径覆盖 |
| P1 后台（锁屏/杀进程/超时） | START_NOT_STICKY + 状态落库 + onTimeout/onDestroy 恢复 |
| P1 MediaStore（权限/Uri/删除/重扫） | 自产产物排除 + NeedConsent 空安全 + 批量删除请求 |
| P1 视频编码（H264/HEVC/4K/HDR…） | 探测改用 MediaExtractor 编码尺寸 + 硬件回退 |
| P2 权限 | READ_MEDIA_VIDEO(33+)/READ_EXTERNAL_STORAGE + 运行时门禁 + ON_RESUME 复检 |
| P2 UI | 通知收尾顺序修复；进度 @Volatile；按钮按状态禁用 |
| P2 性能 | COUNT(*) 统计；批量重排单事务；日志 Channel + trim(3000) |
| P2 存储边界 | 压缩前空间预检（输出×2 + 512MB 余量） |
| P3 Release | R8 keep `androidx.media3.**` + 整个 data 包；枚举 name/valueOf 安全 |
| 状态机 / 文件系统一致性 / 故障注入 | 见下方「状态机与故障注入审计」 |

---

## 二、已发现并修复的 Bug（FIXED）

> 原 `文本2.txt` 第二十五节列出的 BUG-001~013 在文档中为占位文本（「粘贴问题」），本次按其指向的**技术根因**逐一定位、修复并回归。编号 BUG-001~015 为本审计统一编号。

### BUG-001 ｜ P1 视频编码/旋转 — 竖屏视频缩放目标算错
- **根因**：旧代码把「显示高度」直接传给 `Presentation.createForHeight()`。Media3 的 `Presentation` 作用于**解码后的原始帧**（未旋转编码尺寸），而 Transformer 默认保留源 `rotation` 元数据。对 rotation=90/270 的竖屏视频，显示宽高与编码帧宽高互换，导致目标尺寸远超预期（如目标 720P 却输出 1280 高），压缩收益严重缩水甚至反向放大。
- **修复**：`CompressionPlanFactory` 改为输出 `scaleWidth/scaleHeight`（编码帧尺寸），按 `rotation` 做宽高互换映射；`CompressionEngine` 用 `Presentation.createForWidthAndHeight(w, h, SCALE_TO_FIT_WITH_CROP)`。
- **测试**：静态推演（rotation 90/270 互换后 display 尺寸一致）+ 编译通过。

### BUG-002 ｜ P1 MediaStore — 自产压缩产物被反复重压缩
- **根因**：扫描未排除本 App 输出目录 `Movies/VideoCompressor` 与文件名标记 `_compressed`，导致压缩产物被当成新视频再次入队，反复重编码并产生 `_compressed_1/_compressed_2` 雪球文件。
- **修复**：`MediaStoreScanner.isOwnOutput()` 同时按 `RELATIVE_PATH` 含 `VideoCompressor` 与 `DISPLAY_NAME` 含 `_compressed` 排除；并在投影中加入 `MediaStore.Video.Media.DATA` 兜底识别（API<29）。
- **测试**：静态推演（输出文件被过滤）+ 编译通过。

### BUG-003 ｜ P0 数据安全 — 冷启动恢复与运行服务竞态
- **根因**：`App.onCreate` 的恢复逻辑若与正在运行的服务并发执行，可能把「正在编码」的任务误当成上次崩溃残留重置成 INTERRUPTED，导致进度归零、甚至同一视频被压两遍。
- **修复**：`App.kt` 恢复前先判断 `if (CompressionController.running.value) return`，恢复与清理只在服务未运行时进行。
- **测试**：并发路径推演 + 编译通过。

### BUG-004 ｜ P1 任务系统 — 服务被系统杀死后任务永久卡在 PROCESSING
- **根因**：服务被系统/LMKD 回收时，`PROCESSING/VERIFYING` 状态无人复位，用户既看不到进度也无法重试，只能清数据重装。
- **修复**：`onDestroy()` 与冷启动恢复均调用 `resetBusyToInterrupted()`，把 BUSY 态复位为 INTERRUPTED；注意「删除原视频」发生在 COMPLETED 之后，不会误伤。
- **测试**：故障注入推演（压缩中杀进程→重启→INTERRUPTED→可继续）+ 编译通过。

### BUG-005 ｜ P2 UI/通知 — 主循环崩溃后前台通知永久挂起、UI 一直「压缩中」
- **根因**：早期把收尾写在 `runLoop` 末尾，中途异常则跳过，通知与 `running` 状态永不复位。
- **修复**：`loopJob` 的 `finally` 始终执行 `stopForegroundSafely() + stopSelf() + setRunning(false)`；收尾与结果通知解耦。
- **测试**：异常路径推演 + 编译通过。（本次新增 BUG-015 进一步修正结果通知可见性）

### BUG-006 ｜ P1 任务系统 — retryCount 被回滚
- **根因**：`finishAs` 用 `task.retryCount`（循环开始时的旧快照）写回，把开头刚递增的计数又覆盖，失败任务看起来「从未重试过」。
- **修复**：`finishAs` 增加显式 `retryCount` 参数，由调用方传入「本次尝试后的真实次数」，14 处调用点统一传入 `previousRetries + 1`。
- **测试**：重试路径推演（retryCount 单调递增）+ 编译通过。

### BUG-007 ｜ P1 MediaStore — NeedConsent 时 `userAction.actionIntent` 为 null 导致 NPE
- **根因**：部分厂商 ROM 上 `RecoverableSecurityException.userAction?.actionIntent` 为 null，直接 `.intentSender` 抛 NPE，把「压缩成功、只差用户确认」误判为未预期异常 → 任务写成 FAILED。
- **修复**：`MediaStorePublisher.deleteVideo()` 用 `runCatching { e.userAction?.actionIntent?.intentSender }` 取值，null 时返回 `Failed(提示手动删除)` 而非崩溃。
- **测试**：空安全路径推演 + 编译通过。

### BUG-008 ｜ P2 性能 — 统计用 `observeAll()` 全表加载
- **根因**：为了拿任务总数订阅整个 `video_tasks` 表（每个字段全反序列化），任务一多即吃几十 MB 内存且每次变动重算全表。
- **修复**：`combineCounts()` 改用 `observeTotalCount()/observeWaitingCount()/observeDoneCount()/observeFailedCount()/observeSavedBytes()` 的 `COUNT(*)` 组合。
- **测试**：内存路径推演（不再加载全表）+ 编译通过。

### BUG-009 ｜ P2 性能 — 重排队列逐条 `update`（事务×N）
- **根因**：扫描后/改排序方式时循环逐条 `updateQueueOrder`，每次开事务写 WAL，几千任务重排要好几秒。
- **修复**：`TaskDao.updateQueueOrderBatch()` 用 `@Transaction` 单事务写回；`HomeViewModel.updateSettings` 仅在 `sortOrder` 真正变化时才重排。
- **测试**：重排路径推演（单事务）+ 编译通过。

### BUG-010 ｜ P1 视频编码 — 探测依赖 `MediaMetadataRetriever` 已旋转尺寸 → 横竖判反
- **根因**：部分厂商 ROM（OriginOS 等）`METADATA_KEY_VIDEO_WIDTH/HEIGHT` 返回的是**已算旋转的显示尺寸**，再叠加 `rotation` 会把横竖屏判反，进而算出错误缩放目标。
- **修复**：`VideoProbe.probeWithExtractor()` 主路径用 `MediaExtractor` + `MediaFormat.KEY_WIDTH/HEIGHT`（语义明确的编码尺寸），仅对 MediaExtractor 解析不了的容器回退 Retriever；并 `normalizeRotation()` 收敛非常规值。
- **测试**：旋转路径推演（编码尺寸与显示尺寸解耦）+ 编译通过。

### BUG-011 ｜ P2 性能/UI — 进度字段非线程安全 → 进度卡死/刷屏
- **根因**：`lastProgress/lastProgressAt` 被引擎线程与主循环同时读写，无 `@Volatile` 时另一线程可能一直读旧值。
- **修复**：两字段加 `@Volatile`；`onEngineProgress` 节流（变化≥2% 且距上次≥400ms 才更新）。
- **测试**：并发读写推演 + 编译通过。

### BUG-012 ｜ P1 后台 — `onTimeout` 用 `runBlocking` 占主线程
- **根因**：Android 15 `mediaProcessing` 6 小时额度耗尽回调在主线程，硬等 DB 写入有 ANR 风险。
- **修复**：`handleTimeout()` 改用 `scope.launch { resetBusyToInterrupted() }` 异步；只把 BUSY 态复位为 INTERRUPTED，绝不标记完成、绝不删原视频。
- **测试**：超时路径推演 + 编译通过。

### BUG-013 ｜ P1 视频编码 — 重试复用脏 Transformer 实例，第二次以相同方式再失败
- **根因**：编码失败后直接复用同一 `Transformer` 实例重试，其内部状态可能已脏，白白多等一个视频的编码时间。
- **修复**：重试前 `resetEngine()`（shutdown 旧实例）再 `ensureEngine()` 新建干净实例。
- **测试**：重试路径推演 + 编译通过。

### BUG-014 ｜ P1 视频编码/验证 — 校验基准不可靠、缺少放大/收益闸门
- **根因**：旧校验用扫描时的元数据快照（可能漏解析旋转/音轨）做基准，且缺少「输出被放大」「体积无收益」等闸门，存在误判通过/误删风险。
- **修复**：`OutputVerifier` 统一用 `VideoProbe.probe()`（与源同逻辑）读取输出；新增 `VERIFY_UPSCALED`（输出像素 > 源×1.02 → 无效）、方向/比例/音轨一致性、时长容差、`minSavingsPercent` 收益检查。
- **测试**：校验矩阵推演（方向/比例/放大/音轨/收益全部有闸门）+ 编译通过。

### BUG-015 ｜ P2 UI/通知 — 结果通知被立即删除，用户看不到完成/取消/超时结果 【本次新增修复】
- **根因**：`CompressionService` 的 `finally`（以及 `handleTimeout`）先 `notifier.finish(summary)` 发结果通知，再 `stopForegroundSafely()`（`STOP_FOREGROUND_REMOVE`）——后一步把刚发的通知一并删除，用户永远看不到「完成 / 已取消 / 已暂停」的结果，误以为 App 无响应。
- **修复**：
  1. `NotificationHelper.build()` 增加 `ongoing` 参数；`finish()`/`updateStopped()` 的结果通知改为 `setOngoing(false) + setAutoCancel(true)`（常驻进行中通知仍为 ongoing）。
  2. `finally` 与 `handleTimeout` 均调整为**先 `stopForegroundSafely()` 再发结果通知**，使结果通知以普通可点击通知保留。
- **测试**：收尾顺序推演（先 detach 前台再发结果）+ 编译通过。

---

## 三、已知 / 受限项（KNOWN / BLOCKED，未伪造为已修复）

### KNOWN-001 ｜ P1 MediaStore/删除 — Android 10/11 单 Uri 删除确认
- **现状**：`COMPLETED_BUT_ORIGINAL_DELETE_FAILED` 任务在 **Android 11+（API 30+）** 通过 `MediaStorePublisher.createBatchDeleteRequest()` + `StartIntentSenderForResult` 完成删除（UI 已接好，见 `TasksScreen`）。但 **Android 10/11（API 29-30）** 删除需 `RecoverableSecurityException` 的**单 Uri `IntentSender`**，该 sender 无法跨进程死亡持久化（代码中 `deleteVideo` 取到 sender 后只写日志、未持久化），兜底 `retryOne` 在该区间可能再次需确认。
- **影响**：VIVO 主力机型为 Android 12+，已覆盖；API 29-30 极少数设备删除原视频需用户在相册手动确认。
- **是否需要真机**：是（API 29-30 真机/模拟器回归）。
- **是否系统限制**：部分——属于 Android 存储权限模型的客观约束。

### KNOWN-002 ｜ P2 资源/媒体 — 编码中途进程死亡残留孤立 pending 文件
- **现状**：API 29+ 发布用 `IS_PENDING=1`，若进程在 `openOutputStream→copyTo` 之间被杀死，该 MediaStore 条目未定稿，残留孤立 pending 文件（输出未被采用，原视频保留，无数据风险）。
- **缓解**：下次启动 `clearTempFiles()` 清理 App 私有临时目录；孤立 pending 相册条目需系统/重启回收。
- **是否系统限制**：是（MediaStore pending 原子性由系统保证，异常中断无法 100% 避免）。

### KNOWN-003 ｜ P2 存储边界 — 压缩过程中存储空间耗尽
- **现状**：已做压缩前空间预检（预估输出×2 + 512MB 余量）。若**压缩中途**空间耗尽，Media3 失败 → 任务 `FAILED`、原视频保留、临时文件清理——**已正确处理，非数据丢失**；用户需释放空间后重试。
- **是否系统限制**：否（已按最保守原则处理）。

### KNOWN-004 ｜ P3 Release — R8/混淆运行时回归
- **现状**：`proguard-rules.pro` 已 `-keep androidx.media3.**` 与 `-keep com.videocompress.local.data.**`（枚举 `name()/valueOf`、Room 实体/DAO 安全），Release 构建 **BUILD SUCCESSFUL**。但混淆后 Media3/Transformer 的真实编码行为需在**真机**回归。
- **是否需要真机**：是（真机安装 Release 包并压一个视频）。

---

## 四、状态机与故障注入审计（spec 四/五/六）

**状态机**：`PENDING → PROCESSING → VERIFYING → COMPLETED →(删除原视频)`；异常 `PROCESSING → FAILED/CANCELLED/SKIPPED`；`VERIFYING → COMPLETED_NO_GAIN`；`COMPLETED → COMPLETED_BUT_ORIGINAL_DELETE_FAILED`。

**逐状态「App 在该瞬间死亡」审计**：
| 状态 | 死亡后 | 重启恢复 | 数据安全 |
|---|---|---|---|
| PENDING | 无副作用 | 正常入队 | ✅ |
| PROCESSING | 原视频未动 | `resetBusyToInterrupted`→INTERRUPTED→可继续 | ✅ 不删原视频 |
| VERIFYING | temp 已清理/孤立 | 同上 | ✅ |
| COMPLETED（未删原） | 输出已落库 | 直接终态 | ✅ |
| COMPLETED（删原中） | 原可能仍在 | `COMPLETED_BUT_ORIGINAL_DELETE_FAILED`→用户确认 | ✅ 最保守 |
| DELETE_FAILED | 原保留 | 等待确认 | ✅ |

**文件系统一致性（spec 六 A–E）**：所有「压缩成功」分支都先 `updateOutput`+`updateStatus(COMPLETED)` **再**删原视频，杜绝「原视频已删但状态还是 PENDING」的灾难状态；任何一步校验不过都 `FAILED` 且原视频保留。

**故障注入（spec 五）**：压缩 1%/30%/50%/80% 杀进程、锁屏、强制停止、验证中杀进程、删除原视频中杀进程——均落在上述状态机的「安全恢复」路径上，原视频不会被误删。

---

## 五、编译与验收状态（spec 二十一/二十三）

| 项目 | 状态 |
|---|---|
| `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL（本会话已验证，40s） |
| `./gradlew assembleRelease` | ✅ BUILD SUCCESSFUL（本次构建验证，见 `release_build.log`） |
| 已知 Bug 回归 | 静态确认逻辑闭环（BUG-001~015 全部有对应修复与推演） |
| 新发现 Bug 处理 | BUG-015 已修复 |
| 数据安全测试 | 删除前双重校验 + 最保守判断，推演通过 |
| 后台/锁屏测试 | 状态机 + onDestroy/onTimeout/onStartCommand(START_NOT_STICKY) 推演通过 |
| VIVO/OriginOS 真机 | ⚠️ **未测试**（无真机，见 KNOWN / 边界说明） |
| 视频兼容性矩阵 A–G | ⚠️ **未实机验证**（无真机+样本，见 KNOWN-004） |
| 压力测试 10/50/100/500 | ⚠️ **未执行**（无真机） |
| Git 稳定版本 | ✅ baseline `86b86a8` + 本次 BUG-015 提交 |

---

## 六、最终结论（遵守 spec §24，不伪造）

> 已完成**系统级 Bug 审计**：全量静态审计 36 个源文件，共定位 **15 个 Bug（BUG-001~015）**，其中 **15 个已修复**（含本次新增的 BUG-015 通知可见性）。另有 **4 项已知/受限项（KNOWN-001~004）** 因 Android 存储模型或**无真机环境**无法在本环境彻底确认，已如实标注所需条件（真实 VIVO 设备 / Logcat / 特定视频样本）。
>
> **Debug 与 Release 均 BUILD SUCCESSFUL**；已知问题修复逻辑经状态机与故障注入推演闭环；数据安全、后台可靠、视频输出校验均按「最保守判断」实现。
>
> **唯一无法仅通过静态分析确认的部分**：VIVO/OriginOS 后台存活、4K/60fps/HDR 实机编码、压力测试、Release 真机回归——这些需要真实设备/Logcat/样本视频验证，未在本环境执行，亦未声称通过。
