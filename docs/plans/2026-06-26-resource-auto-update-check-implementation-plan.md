# Resource Auto Update Check Implementation Plan

> **For agentic workers:** Use `executing-plans` or equivalent step-by-step execution. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional automatic resource update checks that periodically fetch only the PixelText Hub manifest, notify the user when rules/model updates are available, and leave resource installation fully manual.

**Architecture:** Reuse the existing Hub manifest API and manual update flow. Add persisted auto-check settings, a WorkManager worker/scheduler pair for background manifest checks, a dedicated low-priority notification helper, and a settings deep link that opens the settings screen and triggers one manual check. No background path downloads or activates resources.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Koin, SharedPreferences, WorkManager, Retrofit/Moshi, Android notifications.

## Global Constraints

- Follow `AGENTS.md`: do not add unit tests, do not add test dependencies, and do not run `testDebugUnitTest` or `test`.
- The worker must only call the manifest endpoint and must not call `updateAll()`.
- The update notification must use a dedicated low-priority notification channel.
- The update notification must not accumulate; only one update notification may exist at a time.
- Notification click must open the settings screen and trigger one check for resource updates.
- Notification click must not automatically download, install, or activate resources.
- The interval is user-entered in hours and must be a positive integer greater than `0`.
- When automatic checking is enabled, the first interval is calculated from the enable time.
- When the interval is changed while automatic checking is enabled, restart the interval calculation from the change time.
- On app startup, if automatic checking is enabled and the last probe time is older than the configured interval, enqueue one immediate check job.

---

## File Structure

- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
  - Add auto-check settings fields, defaults, keys, setters, and getters.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`
  - Persist auto-check settings in SharedPreferences and expose them through `settings`.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/hub/HubModels.kt`
  - Add `ResourceUpdateDetail` and `ResourceUpdateAvailability` domain models shared by settings UI and worker.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/HubResourceRepository.kt`
  - Add a shared manifest-only availability check method.
- Create: `app/src/main/java/vip/mystery0/pixel/text/notification/ResourceUpdateNotificationHelper.kt`
  - Create the low-priority channel, show/cancel a single fixed notification, and build the settings PendingIntent.
- Create: `app/src/main/java/vip/mystery0/pixel/text/worker/ResourceUpdateCheckWorker.kt`
  - Run manifest-only automatic checks and notify when updates are available.
- Create: `app/src/main/java/vip/mystery0/pixel/text/worker/ResourceUpdateScheduler.kt`
  - Own all WorkManager scheduling, cancellation, interval changes, and app-start overdue checks.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`
  - Register `ResourceUpdateScheduler`.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/PixelTextApp.kt`
  - Create the resource update notification channel and synchronize scheduling at app startup.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/MainActivity.kt`
  - Parse notification extras for settings deep link and one-time check trigger.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`
  - Navigate to settings when requested by `MainActivity` and forward the one-time check trigger to `SettingsScreen`.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`
  - Add setters for automatic check settings and reuse repository availability checking.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`
  - Add the switch and interval input UI; trigger a check when opened from notification.
- Modify: `PRIVACY.md`
  - Document optional automatic manifest checks and clarify that SMS content is not uploaded.

## Task 1: Persist Automatic Check Settings

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`

**Interfaces:**
- Produces:
  - `AppSettings.resourceAutoCheckEnabled: Boolean`
  - `AppSettings.resourceAutoCheckIntervalHours: Long`
  - `AppSettings.resourceAutoCheckLastCheckedAt: Long`
  - `AppSettingsRepository.setResourceAutoCheckEnabled(enabled: Boolean)`
  - `AppSettingsRepository.setResourceAutoCheckIntervalHours(hours: Long)`
  - `AppSettingsRepository.setResourceAutoCheckLastCheckedAt(timestamp: Long)`
  - matching getters.

- [ ] **Step 1: Add settings fields and repository methods**

Add to `AppSettings`:

```kotlin
val resourceAutoCheckEnabled: Boolean =
    AppSettingsKeys.DEFAULT_RESOURCE_AUTO_CHECK_ENABLED,
val resourceAutoCheckIntervalHours: Long =
    AppSettingsKeys.DEFAULT_RESOURCE_AUTO_CHECK_INTERVAL_HOURS,
val resourceAutoCheckLastCheckedAt: Long =
    AppSettingsKeys.DEFAULT_RESOURCE_AUTO_CHECK_LAST_CHECKED_AT,
```

Add to `AppSettingsRepository`:

```kotlin
fun setResourceAutoCheckEnabled(enabled: Boolean)
fun setResourceAutoCheckIntervalHours(hours: Long)
fun setResourceAutoCheckLastCheckedAt(timestamp: Long)
fun isResourceAutoCheckEnabled(): Boolean
fun getResourceAutoCheckIntervalHours(): Long
fun getResourceAutoCheckLastCheckedAt(): Long
```

Add to `AppSettingsKeys`:

```kotlin
const val KEY_RESOURCE_AUTO_CHECK_ENABLED = "resource_auto_check_enabled"
const val KEY_RESOURCE_AUTO_CHECK_INTERVAL_HOURS = "resource_auto_check_interval_hours"
const val KEY_RESOURCE_AUTO_CHECK_LAST_CHECKED_AT = "resource_auto_check_last_checked_at"
const val DEFAULT_RESOURCE_AUTO_CHECK_ENABLED = false
const val DEFAULT_RESOURCE_AUTO_CHECK_INTERVAL_HOURS = 24L
const val DEFAULT_RESOURCE_AUTO_CHECK_LAST_CHECKED_AT = 0L
```

- [ ] **Step 2: Implement SharedPreferences persistence**

In `AppSettingsRepositoryImpl`, add setters/getters using `putBoolean`, `putLong`, `getBoolean`, and `getLong`.

`setResourceAutoCheckIntervalHours(hours)` must persist `hours.coerceAtLeast(1L)` so invalid direct calls cannot store `0` or negative values.

- [ ] **Step 3: Update `readSettings()`**

Populate the three new fields from the getters.

- [ ] **Step 4: Verify**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

## Task 2: Share Manifest Availability Logic

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/hub/HubModels.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/HubResourceRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`

**Interfaces:**
- Produces:
  - `ResourceUpdateDetail`
  - `ResourceUpdateAvailability`
  - `HubResourceRepository.checkResourceUpdateAvailability(): ResourceUpdateAvailability`

- [ ] **Step 1: Move shared update detail models into domain**

Add to `HubModels.kt`:

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

- [ ] **Step 2: Add repository availability check**

Add to `HubResourceRepository`:

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

Add a private `HubResourceManifest.toResourceUpdateDetail()` in the repository file, copied from the current `SettingsViewModel` extension.

- [ ] **Step 3: Update `SettingsViewModel.checkResourceUpdates()`**

Replace inline manifest comparison with `hubResourceRepository.checkResourceUpdateAvailability()`.

When result is `Available`, set `pendingManifest = result.manifest` and `ResourceUpdateState.Available(result.detail)`.

When result is `NoUpdate`, set `pendingManifest = null` and `ResourceUpdateState.NoUpdate(result.message)`.

- [ ] **Step 4: Remove duplicate models from `SettingsViewModel.kt`**

Delete the local `ResourceUpdateDetail` data class and local `HubResourceManifest.toResourceUpdateDetail()` extension after imports point to `domain.hub.ResourceUpdateDetail`.

- [ ] **Step 5: Verify**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

## Task 3: Add Resource Update Notification Helper

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/notification/ResourceUpdateNotificationHelper.kt`

**Interfaces:**
- Produces:
  - `ResourceUpdateNotificationHelper.CHANNEL_ID_RESOURCE_UPDATES`
  - `ResourceUpdateNotificationHelper.createNotificationChannel(context: Context)`
  - `ResourceUpdateNotificationHelper.showUpdateAvailable(context: Context, detail: ResourceUpdateDetail)`
  - `ResourceUpdateNotificationHelper.cancel(context: Context)`

- [ ] **Step 1: Create notification helper**

Create an object with:

```kotlin
object ResourceUpdateNotificationHelper {
    const val CHANNEL_ID_RESOURCE_UPDATES = "channel_resource_updates"
    private const val CHANNEL_NAME = "资源更新"
    private const val CHANNEL_DESC = "规则和模型资源更新提醒"
    private const val NOTIFICATION_ID_RESOURCE_UPDATE = 20020
}
```

- [ ] **Step 2: Create low-priority channel**

Use `NotificationManager.IMPORTANCE_LOW` and `setShowBadge(false)`.

- [ ] **Step 3: Build fixed notification**

`showUpdateAvailable()` must:

- return immediately when Android 13+ notification permission is missing.
- call `createNotificationChannel(context)`.
- call `cancel(context)` before `notify(...)`.
- use `NotificationCompat.PRIORITY_LOW`.
- use `setAutoCancel(true)`.
- use `setOnlyAlertOnce(true)`.
- use `R.drawable.ic_notification_sms`.
- title: `发现资源更新`
- text: `有新的规则或模型资源可用，点按查看`

The PendingIntent target is `MainActivity` with:

```kotlin
putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
putExtra(MainActivity.EXTRA_TRIGGER_RESOURCE_UPDATE_CHECK, true)
```

- [ ] **Step 4: Add cancel helper**

`cancel(context)` calls:

```kotlin
NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_RESOURCE_UPDATE)
```

- [ ] **Step 5: Verify**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

## Task 4: Add Worker and Scheduler

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/worker/ResourceUpdateCheckWorker.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/worker/ResourceUpdateScheduler.kt`

**Interfaces:**
- Produces:
  - `ResourceUpdateCheckWorker`
  - `ResourceUpdateScheduler.syncAfterSettingsChange()`
  - `ResourceUpdateScheduler.syncOnAppStart()`
  - `ResourceUpdateScheduler.enqueueImmediateCheck()`

- [ ] **Step 1: Create `ResourceUpdateCheckWorker`**

Worker dependencies via `KoinComponent`:

```kotlin
private val settingsRepository: AppSettingsRepository by inject()
private val hubResourceRepository: HubResourceRepository by inject()
```

`doWork()` behavior:

1. If `settingsRepository.isResourceAutoCheckEnabled()` is false, cancel the update notification and return `Result.success()`.
2. Call `hubResourceRepository.checkResourceUpdateAvailability()`.
3. On success, call `settingsRepository.setResourceAutoCheckLastCheckedAt(System.currentTimeMillis())`.
4. If `Available`, call `ResourceUpdateNotificationHelper.showUpdateAvailable(applicationContext, result.detail)`.
5. If `NoUpdate`, call `ResourceUpdateNotificationHelper.cancel(applicationContext)`.
6. On exception, log `resource update check failed error=<SimpleName>` and return `Result.retry()`.

Do not call `hubResourceRepository.updateAll(...)`.

- [ ] **Step 2: Create `ResourceUpdateScheduler`**

Constructor:

```kotlin
class ResourceUpdateScheduler(
    private val context: Context,
    private val settingsRepository: AppSettingsRepository,
)
```

Constants:

```kotlin
private const val UNIQUE_PERIODIC_WORK_NAME = "resource_update_auto_check"
private const val UNIQUE_IMMEDIATE_WORK_NAME = "resource_update_immediate_check"
```

- [ ] **Step 3: Implement periodic sync**

`syncAfterSettingsChange()` behavior:

- If disabled:
  - cancel `UNIQUE_PERIODIC_WORK_NAME`
  - cancel `UNIQUE_IMMEDIATE_WORK_NAME`
  - cancel update notification
- If enabled:
  - enqueue unique periodic work with `ExistingPeriodicWorkPolicy.UPDATE`
  - interval: `settingsRepository.getResourceAutoCheckIntervalHours()`
  - initial delay: same interval in hours
  - constraint: `NetworkType.CONNECTED`

- [ ] **Step 4: Implement app-start sync**

`syncOnAppStart()` behavior:

- If disabled, call `syncAfterSettingsChange()` and return.
- Ensure periodic work exists by calling the enabled branch of `syncAfterSettingsChange()`.
- Compute:

```kotlin
val intervalMillis = TimeUnit.HOURS.toMillis(
    settingsRepository.getResourceAutoCheckIntervalHours().coerceAtLeast(1L)
)
val lastCheckedAt = settingsRepository.getResourceAutoCheckLastCheckedAt()
val now = System.currentTimeMillis()
```

- If `lastCheckedAt <= 0L || now - lastCheckedAt >= intervalMillis`, call `enqueueImmediateCheck()`.

- [ ] **Step 5: Implement immediate check**

`enqueueImmediateCheck()` enqueues unique one-time work with `ExistingWorkPolicy.KEEP` and `NetworkType.CONNECTED`.

- [ ] **Step 6: Verify**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

## Task 5: Wire Scheduler and App Startup

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/PixelTextApp.kt`

**Interfaces:**
- Consumes:
  - `ResourceUpdateScheduler`
  - `ResourceUpdateNotificationHelper`

- [ ] **Step 1: Register scheduler in Koin**

Add:

```kotlin
single { ResourceUpdateScheduler(androidContext(), get()) }
```

- [ ] **Step 2: Create notification channel on app startup**

In `PixelTextApp.onCreate()`, call:

```kotlin
ResourceUpdateNotificationHelper.createNotificationChannel(this)
```

- [ ] **Step 3: Synchronize scheduler after Koin startup**

After `startKoin { ... }`, call:

```kotlin
getKoin().get<ResourceUpdateScheduler>().syncOnAppStart()
```

Import `org.koin.android.ext.android.getKoin`.

- [ ] **Step 4: Verify**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

## Task 6: Add Notification Deep Link to Settings

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/MainActivity.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`

**Interfaces:**
- Produces:
  - `MainActivity.EXTRA_OPEN_SETTINGS`
  - `MainActivity.EXTRA_TRIGGER_RESOURCE_UPDATE_CHECK`
  - `SettingsDeepLink`
  - `SettingsScreen(resourceUpdateCheckRequestId: Long?)`

- [ ] **Step 1: Add settings deep link model**

Add:

```kotlin
data class SettingsDeepLink(
    val triggerResourceUpdateCheck: Boolean,
    val requestId: Long,
)
```

Use `requestId` to make repeated notification clicks observable by Compose.

- [ ] **Step 2: Parse notification extras in `MainActivity`**

Add companion constants:

```kotlin
const val EXTRA_OPEN_SETTINGS = "extra_open_settings"
const val EXTRA_TRIGGER_RESOURCE_UPDATE_CHECK = "extra_trigger_resource_update_check"
```

Add a mutable state:

```kotlin
private var pendingSettingsDeepLink by mutableStateOf<SettingsDeepLink?>(null)
```

In `onCreate()` and `onNewIntent()`, parse settings deep link from the intent.

- [ ] **Step 3: Pass settings deep link to navigation**

Extend `AppNavigation` parameters:

```kotlin
pendingSettingsDeepLink: SettingsDeepLink? = null,
onSettingsDeepLinkConsumed: () -> Unit = {},
```

Add `LaunchedEffect(pendingSettingsDeepLink)` that navigates to `"settings"` with `launchSingleTop = true`, then calls `onSettingsDeepLinkConsumed()`.

- [ ] **Step 4: Forward check request into `SettingsScreen`**

When rendering `SettingsScreen`, pass:

```kotlin
resourceUpdateCheckRequestId =
    pendingSettingsDeepLink
        ?.takeIf { it.triggerResourceUpdateCheck }
        ?.requestId
```

In `SettingsScreen`, add:

```kotlin
LaunchedEffect(resourceUpdateCheckRequestId) {
    if (resourceUpdateCheckRequestId != null) {
        viewModel.checkResourceUpdates()
    }
}
```

- [ ] **Step 5: Verify**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

## Task 7: Add Settings UI Controls

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`

**Interfaces:**
- Consumes:
  - `ResourceUpdateScheduler`
  - new `AppSettings` fields

- [ ] **Step 1: Inject scheduler into `SettingsViewModel`**

Constructor adds:

```kotlin
private val resourceUpdateScheduler: ResourceUpdateScheduler,
```

Update Koin ViewModel registration if needed.

- [ ] **Step 2: Add ViewModel setters**

Add:

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

- [ ] **Step 3: Add switch preference**

In the “模型与规则” section after “检查资源更新”, add:

```kotlin
SwitchPreference(
    value = settings.resourceAutoCheckEnabled,
    onValueChange = viewModel::setResourceAutoCheckEnabled,
    title = { Text("自动检查资源更新") },
    summary = { Text("定期获取资源清单，有更新时通知你，不会自动下载") },
    icon = { Icon(Icons.Rounded.Sync, contentDescription = null) }
)
```

- [ ] **Step 4: Add interval preference**

Add a `Preference`:

```kotlin
Preference(
    title = { Text("检查间隔时间") },
    summary = { Text("每 ${settings.resourceAutoCheckIntervalHours} 小时检查一次") },
    enabled = settings.resourceAutoCheckEnabled,
    icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
    onClick = { showResourceAutoCheckIntervalDialog = true }
)
```

- [ ] **Step 5: Add interval input dialog**

Dialog behavior:

- Initial text is `settings.resourceAutoCheckIntervalHours.toString()`.
- Use numeric keyboard.
- Confirm button parses `input.trim().toLongOrNull()`.
- If parsed value is `null` or `<= 0L`, show error text `请输入大于 0 的整数`.
- On valid input, call `viewModel.setResourceAutoCheckIntervalHours(value)` and dismiss.

- [ ] **Step 6: Verify**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

## Task 8: Privacy Docs and Manual Verification

**Files:**
- Modify: `PRIVACY.md`

**Interfaces:**
- Consumes:
  - automatic manifest checking behavior from previous tasks.

- [ ] **Step 1: Update privacy policy**

In the `INTERNET` permission explanation and data upload sections, document:

- automatic resource checking is optional and controlled by the “自动检查资源更新” setting.
- automatic checks only request the resource manifest.
- automatic checks do not upload SMS/MMS content, contacts, local parsing results, or spam classification results.
- resource download still happens only after user confirmation in the settings update dialog.

- [ ] **Step 2: Run compile validation**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Optional lint validation**

Run when implementation touches notification, manifest, or Compose imports heavily:

```bash
./gradlew :app:lintDebug
```

Expected: `BUILD SUCCESSFUL` or only pre-existing warnings.

- [ ] **Step 4: Manual validation checklist**

Verify on device or emulator:

- Default state: automatic check switch is off.
- Enabling automatic check stores `lastCheckedAt` close to enable time and schedules periodic work.
- Changing interval while enabled restarts interval calculation from the change time.
- Entering `0`, negative values, blank input, or non-numeric text shows an error and does not save.
- App startup with overdue `lastCheckedAt` enqueues one immediate check job.
- Worker only fetches manifest and never downloads rules/model files.
- Manifest with same versions cancels any existing update notification.
- Manifest with newer rules/model shows one low-priority notification.
- Repeated update checks replace the same notification instead of accumulating multiple notifications.
- Tapping the notification opens settings, triggers one resource update check, and shows the existing update dialog.
- Tapping the notification does not install resources until the user taps “更新”.
- Disabling automatic checks cancels scheduled work and the existing update notification.

