# 资源自动检查更新实现计划

> **给执行 Agent 的说明：** 按任务逐步执行。任务步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 增加一个可选的资源自动检查能力：定期只拉取 PixelText Hub 的 manifest，发现规则或模型资源有更新时通知用户，资源安装仍然完全由用户手动确认。

**架构：** 复用现有 Hub manifest API 和手动更新流程。新增自动检查相关设置、WorkManager 后台检查 Worker、统一调度器、独立的低优先级通知 helper，以及通知点击进入设置页并触发一次检查的 deep link。后台路径只检查 manifest，不下载、不安装、不激活任何资源。

**技术栈：** Kotlin、Jetpack Compose、Material 3、Koin、SharedPreferences、WorkManager、Retrofit/Moshi、Android Notifications。

## 全局约束

- 遵循 `AGENTS.md`：不新增单元测试，不新增测试依赖，不运行 `testDebugUnitTest` 或 `test`。
- Worker 只能请求 manifest，不能调用 `updateAll()`。
- 更新提醒必须使用独立的低优先级通知渠道。
- 更新提醒不能累积；同一时间最多只显示一条资源更新通知。
- 点击通知只能打开设置页，并触发一次资源更新检查。
- 点击通知不能自动下载、安装或激活资源。
- 检查间隔由用户输入，单位为小时，必须是大于 `0` 的正整数。
- 开启自动检查时，第一次间隔从开启时间开始计算。
- 自动检查开启状态下修改间隔时，从修改时间重新计算下一次间隔。
- App 启动时，如果自动检查已开启且上次探测时间已经超过配置间隔，需要立即 enqueue 一次检查 job。

---

## 文件结构

- 修改：`app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
  - 增加自动检查相关设置字段、默认值、key、setter 和 getter。
- 修改：`app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`
  - 使用 SharedPreferences 持久化自动检查设置，并通过 `settings` 暴露。
- 修改：`app/src/main/java/vip/mystery0/pixel/text/domain/hub/HubModels.kt`
  - 增加设置页和 Worker 共用的 `ResourceUpdateDetail` 与 `ResourceUpdateAvailability` 领域模型。
- 修改：`app/src/main/java/vip/mystery0/pixel/text/data/repository/HubResourceRepository.kt`
  - 增加一个只检查 manifest 的更新可用性判断方法。
- 新建：`app/src/main/java/vip/mystery0/pixel/text/notification/ResourceUpdateNotificationHelper.kt`
  - 创建低优先级通知渠道、显示/取消固定 ID 的单条通知、构造进入设置页的 PendingIntent。
- 新建：`app/src/main/java/vip/mystery0/pixel/text/worker/ResourceUpdateCheckWorker.kt`
  - 执行后台 manifest 检查，发现更新后发通知。
- 新建：`app/src/main/java/vip/mystery0/pixel/text/worker/ResourceUpdateScheduler.kt`
  - 统一管理 WorkManager 的定时调度、取消、间隔变更和 App 启动 overdue 检查。
- 修改：`app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`
  - 注册 `ResourceUpdateScheduler`。
- 修改：`app/src/main/java/vip/mystery0/pixel/text/PixelTextApp.kt`
  - 创建资源更新通知渠道，并在 App 启动时同步调度状态。
- 修改：`app/src/main/java/vip/mystery0/pixel/text/MainActivity.kt`
  - 解析通知 extra，用于进入设置页并触发一次检查。
- 修改：`app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`
  - 根据 `MainActivity` 传入的 deep link 跳转设置页，并把一次性检查请求传给 `SettingsScreen`。
- 修改：`app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`
  - 增加自动检查设置的写入逻辑，并复用 repository 的更新可用性判断。
- 修改：`app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`
  - 增加自动检查开关和检查间隔输入 UI；从通知打开时触发一次检查。
- 修改：`PRIVACY.md`
  - 说明可选的自动 manifest 检查行为，并明确不会上传短信内容。

## 任务 1：持久化自动检查设置

**文件：**
- 修改：`app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`

**接口：**
- 产出：
  - `AppSettings.resourceAutoCheckEnabled: Boolean`
  - `AppSettings.resourceAutoCheckIntervalHours: Long`
  - `AppSettings.resourceAutoCheckLastCheckedAt: Long`
  - `AppSettingsRepository.setResourceAutoCheckEnabled(enabled: Boolean)`
  - `AppSettingsRepository.setResourceAutoCheckIntervalHours(hours: Long)`
  - `AppSettingsRepository.setResourceAutoCheckLastCheckedAt(timestamp: Long)`
  - 对应 getter。

- [ ] **步骤 1：增加设置字段和 repository 方法**

在 `AppSettings` 中增加：

```kotlin
val resourceAutoCheckEnabled: Boolean =
    AppSettingsKeys.DEFAULT_RESOURCE_AUTO_CHECK_ENABLED,
val resourceAutoCheckIntervalHours: Long =
    AppSettingsKeys.DEFAULT_RESOURCE_AUTO_CHECK_INTERVAL_HOURS,
val resourceAutoCheckLastCheckedAt: Long =
    AppSettingsKeys.DEFAULT_RESOURCE_AUTO_CHECK_LAST_CHECKED_AT,
```

在 `AppSettingsRepository` 中增加：

```kotlin
fun setResourceAutoCheckEnabled(enabled: Boolean)
fun setResourceAutoCheckIntervalHours(hours: Long)
fun setResourceAutoCheckLastCheckedAt(timestamp: Long)
fun isResourceAutoCheckEnabled(): Boolean
fun getResourceAutoCheckIntervalHours(): Long
fun getResourceAutoCheckLastCheckedAt(): Long
```

在 `AppSettingsKeys` 中增加：

```kotlin
const val KEY_RESOURCE_AUTO_CHECK_ENABLED = "resource_auto_check_enabled"
const val KEY_RESOURCE_AUTO_CHECK_INTERVAL_HOURS = "resource_auto_check_interval_hours"
const val KEY_RESOURCE_AUTO_CHECK_LAST_CHECKED_AT = "resource_auto_check_last_checked_at"
const val DEFAULT_RESOURCE_AUTO_CHECK_ENABLED = false
const val DEFAULT_RESOURCE_AUTO_CHECK_INTERVAL_HOURS = 24L
const val DEFAULT_RESOURCE_AUTO_CHECK_LAST_CHECKED_AT = 0L
```

- [ ] **步骤 2：实现 SharedPreferences 持久化**

在 `AppSettingsRepositoryImpl` 中增加 setter/getter，分别使用 `putBoolean`、`putLong`、`getBoolean` 和 `getLong`。

`setResourceAutoCheckIntervalHours(hours)` 必须持久化 `hours.coerceAtLeast(1L)`，避免直接调用时写入 `0` 或负数。

- [ ] **步骤 3：更新 `readSettings()`**

从 getter 中读取并填充三个新字段。

- [ ] **步骤 4：验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：`BUILD SUCCESSFUL`。

## 任务 2：抽取共用的 manifest 更新可用性判断

**文件：**
- 修改：`app/src/main/java/vip/mystery0/pixel/text/domain/hub/HubModels.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/data/repository/HubResourceRepository.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`

**接口：**
- 产出：
  - `ResourceUpdateDetail`
  - `ResourceUpdateAvailability`
  - `HubResourceRepository.checkResourceUpdateAvailability(): ResourceUpdateAvailability`

- [ ] **步骤 1：把更新详情模型移动到 domain**

在 `HubModels.kt` 中增加：

```kotlin
data class ResourceUpdateDetail(
    val modelVersion: String,
    val modelSizeBytes: Long?,
    val ruleVersion: String,
    val ruleSizeBytes: Long?,
    val releaseNotes: String,
)

sealed interface ResourceUpdateAvailability {
    data class Available(
        val manifest: HubResourceManifest,
        val detail: ResourceUpdateDetail,
    ) : ResourceUpdateAvailability

    data class NoUpdate(
        val message: String,
    ) : ResourceUpdateAvailability
}
```

- [ ] **步骤 2：增加 repository 更新可用性检查**

在 `HubResourceRepository` 中增加：

```kotlin
suspend fun checkResourceUpdateAvailability(): ResourceUpdateAvailability {
    val manifest = checkManifest()
    val remoteRuleVersion = manifest.rules?.version ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION
    val remoteModelVersion =
        manifest.spamModel?.version ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION
    val currentRuleVersion = settings.getRuleResourceVersion()
    val currentModelVersion = settings.getSpamModelResourceVersion()
    return if (
        remoteRuleVersion == currentRuleVersion &&
        remoteModelVersion == currentModelVersion
    ) {
        ResourceUpdateAvailability.NoUpdate("当前规则和模型已经是最新版本")
    } else {
        ResourceUpdateAvailability.Available(
            manifest = manifest,
            detail = manifest.toResourceUpdateDetail()
        )
    }
}
```

在同一文件中增加私有扩展方法 `HubResourceManifest.toResourceUpdateDetail()`，内容从当前 `SettingsViewModel` 的同名扩展迁移过来。

- [ ] **步骤 3：更新 `SettingsViewModel.checkResourceUpdates()`**

把当前手写的 manifest 版本比较替换为 `hubResourceRepository.checkResourceUpdateAvailability()`。

当结果是 `Available` 时，设置 `pendingManifest = result.manifest`，并设置 `ResourceUpdateState.Available(result.detail)`。

当结果是 `NoUpdate` 时，设置 `pendingManifest = null`，并设置 `ResourceUpdateState.NoUpdate(result.message)`。

- [ ] **步骤 4：删除 `SettingsViewModel.kt` 中的重复模型**

删除本地 `ResourceUpdateDetail` data class 和本地 `HubResourceManifest.toResourceUpdateDetail()` 扩展方法。

`SettingsViewModel` 和 `SettingsScreen` 改为导入 `domain.hub.ResourceUpdateDetail`。

- [ ] **步骤 5：验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：`BUILD SUCCESSFUL`。

## 任务 3：新增资源更新通知 helper

**文件：**
- 新建：`app/src/main/java/vip/mystery0/pixel/text/notification/ResourceUpdateNotificationHelper.kt`

**接口：**
- 产出：
  - `ResourceUpdateNotificationHelper.CHANNEL_ID_RESOURCE_UPDATES`
  - `ResourceUpdateNotificationHelper.createNotificationChannel(context: Context)`
  - `ResourceUpdateNotificationHelper.showUpdateAvailable(context: Context, detail: ResourceUpdateDetail)`
  - `ResourceUpdateNotificationHelper.cancel(context: Context)`

- [ ] **步骤 1：创建通知 helper**

创建 object：

```kotlin
object ResourceUpdateNotificationHelper {
    const val CHANNEL_ID_RESOURCE_UPDATES = "channel_resource_updates"
    private const val CHANNEL_NAME = "资源更新"
    private const val CHANNEL_DESC = "规则和模型资源更新提醒"
    private const val NOTIFICATION_ID_RESOURCE_UPDATE = 20020
}
```

- [ ] **步骤 2：创建低优先级通知渠道**

使用 `NotificationManager.IMPORTANCE_LOW`，并设置 `setShowBadge(false)`。

- [ ] **步骤 3：构造固定 ID 通知**

`showUpdateAvailable()` 必须满足：

- Android 13+ 缺少通知权限时直接返回。
- 调用 `createNotificationChannel(context)`。
- 调用 `cancel(context)` 后再 `notify(...)`。
- 使用 `NotificationCompat.PRIORITY_LOW`。
- 使用 `setAutoCancel(true)`。
- 使用 `setOnlyAlertOnce(true)`。
- 使用 `R.drawable.ic_notification_sms`。
- 标题：`发现资源更新`。
- 正文：`有新的规则或模型资源可用，点按查看`。

PendingIntent 打开 `MainActivity`，并带上：

```kotlin
putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
putExtra(MainActivity.EXTRA_TRIGGER_RESOURCE_UPDATE_CHECK, true)
```

- [ ] **步骤 4：增加取消通知方法**

`cancel(context)` 调用：

```kotlin
NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_RESOURCE_UPDATE)
```

- [ ] **步骤 5：验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：`BUILD SUCCESSFUL`。

## 任务 4：新增 Worker 和 Scheduler

**文件：**
- 新建：`app/src/main/java/vip/mystery0/pixel/text/worker/ResourceUpdateCheckWorker.kt`
- 新建：`app/src/main/java/vip/mystery0/pixel/text/worker/ResourceUpdateScheduler.kt`

**接口：**
- 产出：
  - `ResourceUpdateCheckWorker`
  - `ResourceUpdateScheduler.syncAfterSettingsChange()`
  - `ResourceUpdateScheduler.syncOnAppStart()`
  - `ResourceUpdateScheduler.enqueueImmediateCheck()`

- [ ] **步骤 1：创建 `ResourceUpdateCheckWorker`**

Worker 通过 `KoinComponent` 注入：

```kotlin
private val settingsRepository: AppSettingsRepository by inject()
private val hubResourceRepository: HubResourceRepository by inject()
```

`doWork()` 行为：

1. 如果 `settingsRepository.isResourceAutoCheckEnabled()` 为 false，取消资源更新通知，并返回 `Result.success()`。
2. 调用 `hubResourceRepository.checkResourceUpdateAvailability()`。
3. 成功后调用 `settingsRepository.setResourceAutoCheckLastCheckedAt(System.currentTimeMillis())`。
4. 如果结果是 `Available`，调用 `ResourceUpdateNotificationHelper.showUpdateAvailable(applicationContext, result.detail)`。
5. 如果结果是 `NoUpdate`，调用 `ResourceUpdateNotificationHelper.cancel(applicationContext)`。
6. 捕获异常时记录日志 `resource update check failed error=<SimpleName>`，并返回 `Result.retry()`。

禁止调用 `hubResourceRepository.updateAll(...)`。

- [ ] **步骤 2：创建 `ResourceUpdateScheduler`**

构造函数：

```kotlin
class ResourceUpdateScheduler(
    private val context: Context,
    private val settingsRepository: AppSettingsRepository,
)
```

常量：

```kotlin
private const val UNIQUE_PERIODIC_WORK_NAME = "resource_update_auto_check"
private const val UNIQUE_IMMEDIATE_WORK_NAME = "resource_update_immediate_check"
```

- [ ] **步骤 3：实现周期调度同步**

`syncAfterSettingsChange()` 行为：

- 如果自动检查关闭：
  - cancel `UNIQUE_PERIODIC_WORK_NAME`
  - cancel `UNIQUE_IMMEDIATE_WORK_NAME`
  - 取消资源更新通知
- 如果自动检查开启：
  - 使用 `ExistingPeriodicWorkPolicy.UPDATE` enqueue unique periodic work
  - interval 使用 `settingsRepository.getResourceAutoCheckIntervalHours()`
  - initial delay 使用同样的小时数
  - 约束条件为 `NetworkType.CONNECTED`

- [ ] **步骤 4：实现 App 启动同步**

`syncOnAppStart()` 行为：

- 如果自动检查关闭，调用 `syncAfterSettingsChange()` 后返回。
- 如果自动检查开启，先调用开启分支确保 periodic work 存在。
- 计算：

```kotlin
val intervalMillis = TimeUnit.HOURS.toMillis(
    settingsRepository.getResourceAutoCheckIntervalHours().coerceAtLeast(1L)
)
val lastCheckedAt = settingsRepository.getResourceAutoCheckLastCheckedAt()
val now = System.currentTimeMillis()
```

- 如果 `lastCheckedAt <= 0L || now - lastCheckedAt >= intervalMillis`，调用 `enqueueImmediateCheck()`。

- [ ] **步骤 5：实现立即检查**

`enqueueImmediateCheck()` 使用 `ExistingWorkPolicy.KEEP` 和 `NetworkType.CONNECTED` enqueue unique one-time work。

- [ ] **步骤 6：验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：`BUILD SUCCESSFUL`。

## 任务 5：接入 Scheduler 和 App 启动逻辑

**文件：**
- 修改：`app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/PixelTextApp.kt`

**接口：**
- 消费：
  - `ResourceUpdateScheduler`
  - `ResourceUpdateNotificationHelper`

- [ ] **步骤 1：在 Koin 中注册 scheduler**

增加：

```kotlin
single { ResourceUpdateScheduler(androidContext(), get()) }
```

- [ ] **步骤 2：App 启动时创建通知渠道**

在 `PixelTextApp.onCreate()` 中调用：

```kotlin
ResourceUpdateNotificationHelper.createNotificationChannel(this)
```

- [ ] **步骤 3：Koin 启动后同步调度状态**

在 `startKoin { ... }` 之后调用：

```kotlin
getKoin().get<ResourceUpdateScheduler>().syncOnAppStart()
```

导入：

```kotlin
import org.koin.android.ext.android.getKoin
```

- [ ] **步骤 4：验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：`BUILD SUCCESSFUL`。

## 任务 6：新增通知进入设置页的 deep link

**文件：**
- 修改：`app/src/main/java/vip/mystery0/pixel/text/MainActivity.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`

**接口：**
- 产出：
  - `MainActivity.EXTRA_OPEN_SETTINGS`
  - `MainActivity.EXTRA_TRIGGER_RESOURCE_UPDATE_CHECK`
  - `SettingsDeepLink`
  - `SettingsScreen(resourceUpdateCheckRequestId: Long?)`

- [ ] **步骤 1：增加设置页 deep link 模型**

增加：

```kotlin
data class SettingsDeepLink(
    val triggerResourceUpdateCheck: Boolean,
    val requestId: Long,
)
```

`requestId` 用来让连续点击通知也能被 Compose 观察到。

- [ ] **步骤 2：在 `MainActivity` 解析通知 extras**

增加 companion constants：

```kotlin
const val EXTRA_OPEN_SETTINGS = "extra_open_settings"
const val EXTRA_TRIGGER_RESOURCE_UPDATE_CHECK = "extra_trigger_resource_update_check"
```

增加状态：

```kotlin
private var pendingSettingsDeepLink by mutableStateOf<SettingsDeepLink?>(null)
```

在 `onCreate()` 和 `onNewIntent()` 中从 intent 解析 settings deep link。

- [ ] **步骤 3：把 settings deep link 传给 navigation**

扩展 `AppNavigation` 参数：

```kotlin
pendingSettingsDeepLink: SettingsDeepLink? = null,
onSettingsDeepLinkConsumed: () -> Unit = {},
```

增加 `LaunchedEffect(pendingSettingsDeepLink)`：

- 如果 deep link 非空，`navController.navigate("settings") { launchSingleTop = true }`
- 然后调用 `onSettingsDeepLinkConsumed()`

- [ ] **步骤 4：把检查请求传入 `SettingsScreen`**

渲染 `SettingsScreen` 时传入：

```kotlin
resourceUpdateCheckRequestId =
    pendingSettingsDeepLink
        ?.takeIf { it.triggerResourceUpdateCheck }
        ?.requestId
```

在 `SettingsScreen` 中增加：

```kotlin
LaunchedEffect(resourceUpdateCheckRequestId) {
    if (resourceUpdateCheckRequestId != null) {
        viewModel.checkResourceUpdates()
    }
}
```

- [ ] **步骤 5：验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：`BUILD SUCCESSFUL`。

## 任务 7：增加设置页 UI 控件

**文件：**
- 修改：`app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`
- 修改：`app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`

**接口：**
- 消费：
  - `ResourceUpdateScheduler`
  - 新增的 `AppSettings` 字段

- [ ] **步骤 1：向 `SettingsViewModel` 注入 scheduler**

构造函数增加：

```kotlin
private val resourceUpdateScheduler: ResourceUpdateScheduler,
```

如有需要，同步更新 Koin ViewModel 注册。

- [ ] **步骤 2：增加 ViewModel setter**

增加：

```kotlin
fun setResourceAutoCheckEnabled(enabled: Boolean) {
    settingsRepository.setResourceAutoCheckEnabled(enabled)
    if (enabled) {
        settingsRepository.setResourceAutoCheckLastCheckedAt(System.currentTimeMillis())
    }
    resourceUpdateScheduler.syncAfterSettingsChange()
}

fun setResourceAutoCheckIntervalHours(hours: Long): Boolean {
    if (hours <= 0L) return false
    settingsRepository.setResourceAutoCheckIntervalHours(hours)
    if (settingsRepository.isResourceAutoCheckEnabled()) {
        settingsRepository.setResourceAutoCheckLastCheckedAt(System.currentTimeMillis())
    }
    resourceUpdateScheduler.syncAfterSettingsChange()
    return true
}
```

- [ ] **步骤 3：增加开关设置项**

在“模型与规则”区域的“检查资源更新”之后增加：

```kotlin
SwitchPreference(
    value = settings.resourceAutoCheckEnabled,
    onValueChange = viewModel::setResourceAutoCheckEnabled,
    title = { Text("自动检查资源更新") },
    summary = { Text("定期获取资源清单，有更新时通知你，不会自动下载") },
    icon = { Icon(Icons.Rounded.Sync, contentDescription = null) }
)
```

- [ ] **步骤 4：增加间隔设置项**

增加一个 `Preference`：

```kotlin
Preference(
    title = { Text("检查间隔时间") },
    summary = { Text("每 ${settings.resourceAutoCheckIntervalHours} 小时检查一次") },
    enabled = settings.resourceAutoCheckEnabled,
    icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
    onClick = { showResourceAutoCheckIntervalDialog = true }
)
```

- [ ] **步骤 5：增加间隔输入弹窗**

弹窗行为：

- 初始文本为 `settings.resourceAutoCheckIntervalHours.toString()`。
- 使用数字键盘。
- 确认按钮解析 `input.trim().toLongOrNull()`。
- 如果解析结果为 `null` 或 `<= 0L`，显示错误文案 `请输入大于 0 的整数`。
- 输入合法时调用 `viewModel.setResourceAutoCheckIntervalHours(value)` 并关闭弹窗。

- [ ] **步骤 6：验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：`BUILD SUCCESSFUL`。

## 任务 8：更新隐私文档与手动验证

**文件：**
- 修改：`PRIVACY.md`

**接口：**
- 消费：
  - 前面任务实现的自动 manifest 检查行为。

- [ ] **步骤 1：更新隐私政策**

在 `INTERNET` 权限说明和数据上传相关段落中说明：

- 自动资源检查是可选功能，由“自动检查资源更新”设置控制。
- 自动检查只请求资源 manifest。
- 自动检查不会上传短信/彩信内容、联系人、本地解析结果或骚扰分类结果。
- 资源下载仍然只会在用户于设置页更新对话框中确认后发生。

- [ ] **步骤 2：运行编译验证**

运行：

```bash
./gradlew :app:compileDebugKotlin
```

预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 3：可选运行 lint 验证**

当实现大量修改通知、manifest 或 Compose imports 时运行：

```bash
./gradlew :app:lintDebug
```

预期：`BUILD SUCCESSFUL` 或仅存在实现前已有的 warning。

- [ ] **步骤 4：手动验证清单**

在真机或模拟器上验证：

- 默认状态下，“自动检查资源更新”开关关闭。
- 开启自动检查后，`lastCheckedAt` 接近开启时间，并且周期任务被调度。
- 自动检查开启时修改间隔，会从修改时间重新计算下一次间隔。
- 输入 `0`、负数、空白或非数字文本时显示错误，不保存。
- App 启动时，如果 `lastCheckedAt` 已超过间隔，会 enqueue 一次立即检查。
- Worker 只拉取 manifest，不下载规则或模型文件。
- manifest 版本相同时，取消已有资源更新通知。
- manifest 有新规则或模型版本时，显示一条低优先级通知。
- 重复检查时替换同一条通知，不累积多条通知。
- 点击通知进入设置页，并触发一次资源更新检查，显示现有更新对话框。
- 点击通知不会安装资源，只有用户点击“更新”后才会安装。
- 关闭自动检查后，已调度任务和已有资源更新通知都会被取消。

