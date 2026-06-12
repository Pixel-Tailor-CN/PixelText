# Pixel Text Hub App Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add PixelText App support for user-initiated desensitized sample submission and manual rules/model updates from Pixel Text Hub.

**Architecture:** Keep SMS parsing and spam detection on-device. Add a small Hub client, a local resource store, and settings UI entry points; downloaded resources are verified by SHA-256 and stored in app-private files, while bundled assets remain the fallback.

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines, Koin, SharedPreferences, `HttpURLConnection`, `org.json`, Android app-private storage

---

## File Structure

- Modify: `gradle/libs.versions.toml`
  - No new dependency is required for the first phase.
- Modify: `app/build.gradle.kts`
  - Add `BuildConfig.PIXEL_TEXT_HUB_BASE_URL`.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`
  - Register Hub client, resource store, resource updater, and sample repository.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
  - Store downloaded rules/model/vocab versions and last update time.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`
  - Persist resource metadata in SharedPreferences.
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/hub/HubModels.kt`
  - Define manifest, sample request, and update result models.
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/source/PixelTextHubClient.kt`
  - Execute HTTPS JSON requests and file downloads.
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/resource/HubResourceStore.kt`
  - Save, verify, activate, and resolve downloaded resources.
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/repository/HubResourceRepository.kt`
  - Orchestrate manifest fetch, download, verification, and metadata updates.
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/repository/SampleSubmissionRepository.kt`
  - Submit user-confirmed desensitized samples with `ANDROID_ID`.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/parser/MessageParser.kt`
  - Load downloaded rules first, then bundled `assets/rules.json`.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/spam/SpamClassifier.kt`
  - Load downloaded model/vocab first, then bundled assets.
- Create: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SampleSubmissionViewModel.kt`
  - Manage sample submission form state.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`
  - Add resource update state and actions.
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SampleSubmissionScreen.kt`
  - Compose UI for manual desensitized sample submission.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`
  - Add sample submission and manual update entry points.
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`
  - Add `sample_submission` route.
- Modify: `PRIVACY.md`
  - Document user-initiated sample upload and `ANDROID_ID` risk-control usage.

## Task 1: Add Hub Base URL Build Config

**Files:**
- Modify: `app/build.gradle.kts`
- Test: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 1: Add a build config field**

Insert this into `android.defaultConfig`:

```kotlin
val hubBaseUrl = providers.gradleProperty("PIXEL_TEXT_HUB_BASE_URL")
    .orElse("https://hub.example.com")
    .get()
buildConfigField("String", "PIXEL_TEXT_HUB_BASE_URL", "\"$hubBaseUrl\"")
```

- [ ] **Step 2: Run compile check**

Run:

```bash
cd "/Users/mystery0/StudioProjects/PixelText" && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: add pixel text hub base url"
```

## Task 2: Persist Hub Resource Metadata

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt`
- Test: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 1: Extend settings models and repository contract**

Add fields to `AppSettings`:

```kotlin
val ruleResourceVersion: String = AppSettingsKeys.DEFAULT_RESOURCE_VERSION,
val spamModelResourceVersion: String = AppSettingsKeys.DEFAULT_RESOURCE_VERSION,
val vocabResourceVersion: String = AppSettingsKeys.DEFAULT_RESOURCE_VERSION,
val resourceUpdatedAt: Long = AppSettingsKeys.DEFAULT_RESOURCE_UPDATED_AT,
```

Add functions to `AppSettingsRepository`:

```kotlin
fun setRuleResourceVersion(version: String)
fun setSpamModelResourceVersion(version: String)
fun setVocabResourceVersion(version: String)
fun setResourceUpdatedAt(timestamp: Long)
fun getRuleResourceVersion(): String
fun getSpamModelResourceVersion(): String
fun getVocabResourceVersion(): String
fun getResourceUpdatedAt(): Long
```

Add keys:

```kotlin
const val KEY_RULE_RESOURCE_VERSION = "rule_resource_version"
const val KEY_SPAM_MODEL_RESOURCE_VERSION = "spam_model_resource_version"
const val KEY_VOCAB_RESOURCE_VERSION = "vocab_resource_version"
const val KEY_RESOURCE_UPDATED_AT = "resource_updated_at"
const val DEFAULT_RESOURCE_VERSION = "builtin"
const val DEFAULT_RESOURCE_UPDATED_AT = 0L
```

- [ ] **Step 2: Implement SharedPreferences reads and writes**

Add methods in `AppSettingsRepositoryImpl`:

```kotlin
override fun setRuleResourceVersion(version: String) {
    prefs.edit { putString(AppSettingsKeys.KEY_RULE_RESOURCE_VERSION, version) }
}

override fun setSpamModelResourceVersion(version: String) {
    prefs.edit { putString(AppSettingsKeys.KEY_SPAM_MODEL_RESOURCE_VERSION, version) }
}

override fun setVocabResourceVersion(version: String) {
    prefs.edit { putString(AppSettingsKeys.KEY_VOCAB_RESOURCE_VERSION, version) }
}

override fun setResourceUpdatedAt(timestamp: Long) {
    prefs.edit { putLong(AppSettingsKeys.KEY_RESOURCE_UPDATED_AT, timestamp) }
}

override fun getRuleResourceVersion(): String =
    prefs.getString(
        AppSettingsKeys.KEY_RULE_RESOURCE_VERSION,
        AppSettingsKeys.DEFAULT_RESOURCE_VERSION
    ) ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION

override fun getSpamModelResourceVersion(): String =
    prefs.getString(
        AppSettingsKeys.KEY_SPAM_MODEL_RESOURCE_VERSION,
        AppSettingsKeys.DEFAULT_RESOURCE_VERSION
    ) ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION

override fun getVocabResourceVersion(): String =
    prefs.getString(
        AppSettingsKeys.KEY_VOCAB_RESOURCE_VERSION,
        AppSettingsKeys.DEFAULT_RESOURCE_VERSION
    ) ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION

override fun getResourceUpdatedAt(): Long =
    prefs.getLong(
        AppSettingsKeys.KEY_RESOURCE_UPDATED_AT,
        AppSettingsKeys.DEFAULT_RESOURCE_UPDATED_AT
    )
```

Update `readSettings()` to populate the new fields.

- [ ] **Step 3: Run compile check**

Run:

```bash
cd "/Users/mystery0/StudioProjects/PixelText" && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/vip/mystery0/pixel/text/domain/settings/AppSettingsRepository.kt \
  app/src/main/java/vip/mystery0/pixel/text/data/repository/AppSettingsRepositoryImpl.kt
git commit -m "feat: persist hub resource metadata"
```

## Task 3: Add Hub Models and HTTP Client

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/domain/hub/HubModels.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/source/PixelTextHubClient.kt`
- Test: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 1: Create Hub models**

```kotlin
package vip.mystery0.pixel.text.domain.hub

data class HubArtifact(
    val version: String,
    val sha256: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val minAppVersionCode: Int,
    val releaseNotes: String
)

data class HubFileArtifact(
    val sha256: String,
    val sizeBytes: Long,
    val downloadUrl: String
)

data class HubSpamModelArtifact(
    val version: String,
    val model: HubFileArtifact,
    val vocab: HubFileArtifact,
    val minAppVersionCode: Int,
    val releaseNotes: String
)

data class HubResourceManifest(
    val rules: HubArtifact?,
    val spamModel: HubSpamModelArtifact?
)

data class SampleSubmissionRequest(
    val deviceId: String,
    val content: String,
    val sender: String?,
    val category: String,
    val appVersion: String,
    val ruleVersion: String,
    val modelVersion: String
)

sealed interface HubOperationResult {
    data object Success : HubOperationResult
    data class Failure(val message: String) : HubOperationResult
}
```

- [ ] **Step 2: Create `PixelTextHubClient`**

```kotlin
package vip.mystery0.pixel.text.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vip.mystery0.pixel.text.domain.hub.HubArtifact
import vip.mystery0.pixel.text.domain.hub.HubFileArtifact
import vip.mystery0.pixel.text.domain.hub.HubResourceManifest
import vip.mystery0.pixel.text.domain.hub.HubSpamModelArtifact
import vip.mystery0.pixel.text.domain.hub.SampleSubmissionRequest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class PixelTextHubClient(
    private val baseUrl: String
) {
    suspend fun fetchManifest(): HubResourceManifest = withContext(Dispatchers.IO) {
        val json = getJson("$baseUrl/api/v1/resources/manifest")
        HubResourceManifest(
            rules = json.optJSONObject("rules")?.toRulesArtifact(),
            spamModel = json.optJSONObject("spamModel")?.toSpamModelArtifact()
        )
    }

    suspend fun submitSample(request: SampleSubmissionRequest) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("deviceId", request.deviceId)
            put("content", request.content)
            put("sender", request.sender)
            put("category", request.category)
            put("appVersion", request.appVersion)
            put("ruleVersion", request.ruleVersion)
            put("modelVersion", request.modelVersion)
        }.toString()
        postJson("$baseUrl/api/v1/samples", body)
    }

    suspend fun downloadTo(url: String, target: File): Long = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("download failed status=${connection.responseCode}")
        }
        target.parentFile?.mkdirs()
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        target.length()
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("request failed status=${connection.responseCode}")
        }
        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    }

    private fun postJson(url: String, body: String) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("request failed status=${connection.responseCode}")
        }
    }

    private fun JSONObject.toRulesArtifact(): HubArtifact = HubArtifact(
        version = getString("version"),
        sha256 = getString("sha256"),
        sizeBytes = getLong("sizeBytes"),
        downloadUrl = getString("downloadUrl"),
        minAppVersionCode = getInt("minAppVersionCode"),
        releaseNotes = optString("releaseNotes")
    )

    private fun JSONObject.toFileArtifact(): HubFileArtifact = HubFileArtifact(
        sha256 = getString("sha256"),
        sizeBytes = getLong("sizeBytes"),
        downloadUrl = getString("downloadUrl")
    )

    private fun JSONObject.toSpamModelArtifact(): HubSpamModelArtifact = HubSpamModelArtifact(
        version = getString("version"),
        model = getJSONObject("model").toFileArtifact(),
        vocab = getJSONObject("vocab").toFileArtifact(),
        minAppVersionCode = getInt("minAppVersionCode"),
        releaseNotes = optString("releaseNotes")
    )

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
```

- [ ] **Step 3: Run compile check**

Run:

```bash
cd "/Users/mystery0/StudioProjects/PixelText" && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/vip/mystery0/pixel/text/domain/hub/HubModels.kt \
  app/src/main/java/vip/mystery0/pixel/text/data/source/PixelTextHubClient.kt
git commit -m "feat: add pixel text hub client"
```

## Task 4: Add Local Hub Resource Store

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/resource/HubResourceStore.kt`
- Test: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 1: Create resource store**

```kotlin
package vip.mystery0.pixel.text.data.resource

import android.content.Context
import java.io.File
import java.security.MessageDigest

class HubResourceStore(
    private val context: Context
) {
    private val root: File = File(context.filesDir, "hub_resources")

    fun activeRulesFile(): File = File(root, "rules/rules.json")
    fun activeModelFile(): File = File(root, "model/spam_classifier.tflite")
    fun activeVocabFile(): File = File(root, "model/vocab.txt")

    fun tempFile(name: String): File = File(root, "tmp/$name")

    fun hasActiveRules(): Boolean = activeRulesFile().isFile
    fun hasActiveModelAndVocab(): Boolean = activeModelFile().isFile && activeVocabFile().isFile

    fun activateRules(tempFile: File) {
        moveIntoPlace(tempFile, activeRulesFile())
    }

    fun activateModelAndVocab(modelTemp: File, vocabTemp: File) {
        moveIntoPlace(modelTemp, activeModelFile())
        moveIntoPlace(vocabTemp, activeVocabFile())
    }

    fun verifySha256(file: File, expected: String) {
        val actual = sha256(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IllegalStateException("sha256 mismatch expected=$expected actual=$actual")
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveIntoPlace(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }
}
```

- [ ] **Step 2: Run compile check**

Run:

```bash
cd "/Users/mystery0/StudioProjects/PixelText" && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/vip/mystery0/pixel/text/data/resource/HubResourceStore.kt
git commit -m "feat: add local hub resource store"
```

## Task 5: Load Downloaded Rules Before Bundled Rules

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/parser/MessageParser.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`
- Test: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 1: Inject `HubResourceStore` into `MessageParser`**

Change constructor:

```kotlin
class MessageParser(
    private val context: Context,
    private val resourceStore: HubResourceStore
) {
```

Add import:

```kotlin
import vip.mystery0.pixel.text.data.resource.HubResourceStore
```

- [ ] **Step 2: Read rules from downloaded file when present**

Replace direct asset reading with:

```kotlin
private fun readRulesJson(): String {
    val activeRules = resourceStore.activeRulesFile()
    if (activeRules.isFile) {
        return activeRules.readText(Charsets.UTF_8)
    }
    return InputStreamReader(context.assets.open("rules.json")).readText()
}
```

Then in `loadRules()` use:

```kotlin
val jsonString = readRulesJson()
```

- [ ] **Step 3: Add reload support**

Add:

```kotlin
fun reloadRules() {
    rules.clear()
    senderIndex.clear()
    signatureIndex.clear()
    keywordRules.clear()
    genericRules.clear()
    loadRules()
}
```

- [ ] **Step 4: Register store and parser in Koin**

In `AppModule.kt`:

```kotlin
single { HubResourceStore(androidContext()) }
single { MessageParser(androidContext(), get()) }
```

- [ ] **Step 5: Run compile check**

Run:

```bash
cd "/Users/mystery0/StudioProjects/PixelText" && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/vip/mystery0/pixel/text/domain/parser/MessageParser.kt \
  app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt
git commit -m "feat: load hub rules before bundled rules"
```

## Task 6: Load Downloaded Spam Model and Vocab

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/spam/SpamClassifier.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/domain/spam/SpamClassifierFactory.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`
- Test: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 1: Inject resource store into classifier**

Change constructor:

```kotlin
class SpamClassifier(
    private val context: Context,
    private val resourceStore: HubResourceStore
) : AutoCloseable {
```

Add import:

```kotlin
import vip.mystery0.pixel.text.data.resource.HubResourceStore
```

- [ ] **Step 2: Load downloaded model when present**

Replace `loadModel(context)` with:

```kotlin
private fun loadModel(context: Context): MappedByteBuffer {
    val activeModel = resourceStore.activeModelFile()
    if (activeModel.isFile) {
        return FileInputStream(activeModel).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, activeModel.length())
        }
    }
    val fd = context.assets.openFd(MODEL_FILE)
    return FileInputStream(fd.fileDescriptor).channel.map(
        FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
    )
}
```

- [ ] **Step 3: Load downloaded vocab when present**

Replace `loadVocabulary(context)` with:

```kotlin
private fun loadVocabulary(context: Context): Map<String, Int> {
    val activeVocab = resourceStore.activeVocabFile()
    if (activeVocab.isFile) {
        return activeVocab.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapIndexed { index, token -> token to index + 1 }.toMap()
        }
    }
    return context.assets.open(VOCAB_FILE).bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.mapIndexed { index, token -> token to index + 1 }.toMap()
    }
}
```

- [ ] **Step 4: Update classifier factory registration**

In `AppModule.kt`:

```kotlin
factory { SpamClassifier(androidContext(), get()) }
single<SpamClassifierFactory> {
    SpamClassifierFactory { SpamClassifier(androidContext(), get()) }
}
```

- [ ] **Step 5: Run compile check**

Run:

```bash
cd "/Users/mystery0/StudioProjects/PixelText" && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/vip/mystery0/pixel/text/domain/spam/SpamClassifier.kt \
  app/src/main/java/vip/mystery0/pixel/text/domain/spam/SpamClassifierFactory.kt \
  app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt
git commit -m "feat: load hub spam model resources"
```

## Task 7: Implement Resource Update Repository

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/repository/HubResourceRepository.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`
- Test: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 1: Create repository**

```kotlin
package vip.mystery0.pixel.text.data.repository

import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.data.source.PixelTextHubClient
import vip.mystery0.pixel.text.domain.hub.HubOperationResult
import vip.mystery0.pixel.text.domain.hub.HubResourceManifest
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

class HubResourceRepository(
    private val client: PixelTextHubClient,
    private val store: HubResourceStore,
    private val settings: AppSettingsRepository,
    private val messageParser: MessageParser
) {
    suspend fun checkManifest(): HubResourceManifest = client.fetchManifest()

    suspend fun updateAll(manifest: HubResourceManifest): HubOperationResult {
        return runCatching {
            manifest.rules?.let { rules ->
                if (rules.minAppVersionCode <= BuildConfig.VERSION_CODE) {
                    val temp = store.tempFile("rules-${rules.version}.json")
                    client.downloadTo(rules.downloadUrl, temp)
                    store.verifySha256(temp, rules.sha256)
                    store.activateRules(temp)
                    settings.setRuleResourceVersion(rules.version)
                    messageParser.reloadRules()
                }
            }

            manifest.spamModel?.let { spamModel ->
                if (spamModel.minAppVersionCode <= BuildConfig.VERSION_CODE) {
                    val modelTemp = store.tempFile("spam-model-${spamModel.version}.tflite")
                    val vocabTemp = store.tempFile("vocab-${spamModel.version}.txt")
                    client.downloadTo(spamModel.model.downloadUrl, modelTemp)
                    client.downloadTo(spamModel.vocab.downloadUrl, vocabTemp)
                    store.verifySha256(modelTemp, spamModel.model.sha256)
                    store.verifySha256(vocabTemp, spamModel.vocab.sha256)
                    store.activateModelAndVocab(modelTemp, vocabTemp)
                    settings.setSpamModelResourceVersion(spamModel.version)
                    settings.setVocabResourceVersion(spamModel.version)
                }
            }

            settings.setResourceUpdatedAt(System.currentTimeMillis())
            HubOperationResult.Success
        }.getOrElse { error ->
            HubOperationResult.Failure(error.message ?: "update failed")
        }
    }
}
```

- [ ] **Step 2: Register repository and client**

In `AppModule.kt`:

```kotlin
single { PixelTextHubClient(BuildConfig.PIXEL_TEXT_HUB_BASE_URL.trimEnd('/')) }
single { HubResourceRepository(get(), get(), get(), get()) }
```

- [ ] **Step 3: Run compile check**

Run:

```bash
cd "/Users/mystery0/StudioProjects/PixelText" && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/vip/mystery0/pixel/text/data/repository/HubResourceRepository.kt \
  app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt
git commit -m "feat: add manual hub resource updater"
```

## Task 8: Add Manual Resource Update UI in Settings

**Files:**
- Modify: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`
- Test: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 1: Add update state to `SettingsViewModel`**

```kotlin
sealed interface ResourceUpdateState {
    data object Idle : ResourceUpdateState
    data object Checking : ResourceUpdateState
    data class Available(val summary: String) : ResourceUpdateState
    data object Updating : ResourceUpdateState
    data class Success(val message: String) : ResourceUpdateState
    data class Error(val message: String) : ResourceUpdateState
}
```

Inject `HubResourceRepository` into `SettingsViewModel` and add:

```kotlin
private val _resourceUpdateState = MutableStateFlow<ResourceUpdateState>(ResourceUpdateState.Idle)
val resourceUpdateState: StateFlow<ResourceUpdateState> = _resourceUpdateState.asStateFlow()
private var pendingManifest: HubResourceManifest? = null

fun checkResourceUpdates() {
    _resourceUpdateState.value = ResourceUpdateState.Checking
    viewModelScope.launch {
        runCatching { hubResourceRepository.checkManifest() }
            .onSuccess { manifest ->
                pendingManifest = manifest
                val rules = manifest.rules?.version ?: "no rules"
                val model = manifest.spamModel?.version ?: "no model"
                _resourceUpdateState.value =
                    ResourceUpdateState.Available("规则 $rules，模型 $model")
            }
            .onFailure { error ->
                _resourceUpdateState.value =
                    ResourceUpdateState.Error(error.message ?: "检查更新失败")
            }
    }
}

fun installResourceUpdates() {
    val manifest = pendingManifest ?: return
    _resourceUpdateState.value = ResourceUpdateState.Updating
    viewModelScope.launch {
        when (val result = hubResourceRepository.updateAll(manifest)) {
            HubOperationResult.Success -> {
                _resourceUpdateState.value = ResourceUpdateState.Success("资源已更新")
            }
            is HubOperationResult.Failure -> {
                _resourceUpdateState.value = ResourceUpdateState.Error(result.message)
            }
        }
    }
}
```

- [ ] **Step 2: Add settings preferences**

In `SettingsScreen`, replace the disabled rules/model version preferences with clickable entries:

```kotlin
Preference(
    title = { Text("离线模型版本") },
    summary = { Text(settings.spamModelResourceVersion) },
    icon = { Icon(Icons.Rounded.CloudOff, contentDescription = null) }
)

Preference(
    title = { Text("智能卡片规则版本") },
    summary = { Text(settings.ruleResourceVersion) },
    icon = { Icon(Icons.Rounded.Style, contentDescription = null) }
)

Preference(
    title = { Text("检查资源更新") },
    summary = { Text(resourceUpdateSummary) },
    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
    onClick = viewModel::checkResourceUpdates
)
```

When `resourceUpdateState` is `Available`, show an `AlertDialog` with confirm action:

```kotlin
TextButton(onClick = viewModel::installResourceUpdates) {
    Text("更新")
}
```

- [ ] **Step 3: Run compile check**

Run:

```bash
cd "/Users/mystery0/StudioProjects/PixelText" && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/vip/mystery0/pixel/text/viewmodel/SettingsViewModel.kt \
  app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt
git commit -m "feat: add manual hub resource updates"
```

## Task 9: Add Sample Submission Repository and Screen

**Files:**
- Create: `app/src/main/java/vip/mystery0/pixel/text/data/repository/SampleSubmissionRepository.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/viewmodel/SampleSubmissionViewModel.kt`
- Create: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SampleSubmissionScreen.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt`
- Modify: `app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt`
- Test: `./gradlew :app:compileDebugKotlin`

- [ ] **Step 1: Create sample repository**

```kotlin
package vip.mystery0.pixel.text.data.repository

import android.content.Context
import android.provider.Settings
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.data.source.PixelTextHubClient
import vip.mystery0.pixel.text.domain.hub.HubOperationResult
import vip.mystery0.pixel.text.domain.hub.SampleSubmissionRequest
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

class SampleSubmissionRepository(
    private val context: Context,
    private val client: PixelTextHubClient,
    private val settings: AppSettingsRepository
) {
    suspend fun submit(
        content: String,
        sender: String?,
        category: String
    ): HubOperationResult {
        val trimmedContent = content.trim()
        if (trimmedContent.length < 6) {
            return HubOperationResult.Failure("样本文本太短")
        }
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        if (deviceId.isBlank()) {
            return HubOperationResult.Failure("无法读取设备标识")
        }
        return runCatching {
            client.submitSample(
                SampleSubmissionRequest(
                    deviceId = deviceId,
                    content = trimmedContent,
                    sender = sender?.trim()?.takeIf { it.isNotBlank() },
                    category = category,
                    appVersion = BuildConfig.VERSION_NAME,
                    ruleVersion = settings.getRuleResourceVersion(),
                    modelVersion = settings.getSpamModelResourceVersion()
                )
            )
            HubOperationResult.Success
        }.getOrElse { error ->
            HubOperationResult.Failure(error.message ?: "提交失败")
        }
    }
}
```

- [ ] **Step 2: Register repository and ViewModel**

```kotlin
single { SampleSubmissionRepository(androidContext(), get(), get()) }
viewModel { SampleSubmissionViewModel(get()) }
```

- [ ] **Step 3: Create `SampleSubmissionViewModel`**

```kotlin
class SampleSubmissionViewModel(
    private val repository: SampleSubmissionRepository
) : ViewModel() {
    var content by mutableStateOf("")
        private set
    var sender by mutableStateOf("")
        private set
    var category by mutableStateOf("verification_code")
        private set
    var agreed by mutableStateOf(false)
        private set
    var submitting by mutableStateOf(false)
        private set
    var resultMessage by mutableStateOf<String?>(null)
        private set

    fun updateContent(value: String) { content = value }
    fun updateSender(value: String) { sender = value }
    fun updateCategory(value: String) { category = value }
    fun updateAgreed(value: Boolean) { agreed = value }
    fun clearResult() { resultMessage = null }

    fun submit() {
        if (!agreed || submitting) return
        submitting = true
        viewModelScope.launch {
            val result = repository.submit(content, sender, category)
            resultMessage = when (result) {
                HubOperationResult.Success -> "样本已提交"
                is HubOperationResult.Failure -> result.message
            }
            submitting = false
        }
    }
}
```

- [ ] **Step 4: Create `SampleSubmissionScreen`**

Build a Compose screen with:

- Top app bar title `贡献脱敏样本`
- Sender `TextField`
- Category selector with values `verification_code`, `bank_transaction`, `express_delivery`, `ticket`, `spam`, `normal`
- Multi-line sample text `TextField`
- Checkbox text explaining that the user has manually desensitized the content and agrees to upload `ANDROID_ID` for risk control
- Submit button enabled only when checkbox is checked and content is nonblank

The confirmation text must be:

```text
我确认已经自行删除或替换姓名、手机号、地址、订单号、银行卡号等敏感信息，并同意提交此脱敏样本及 Android 设备标识用于规则、模型改进和反滥用风控。
```

- [ ] **Step 5: Add navigation route and settings entry**

In `AppNavigation.kt`:

```kotlin
composable("sample_submission") {
    SampleSubmissionScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

Pass `onNavigateToSampleSubmission` into `SettingsScreen` and add a preference:

```kotlin
Preference(
    title = { Text("贡献脱敏短信样本") },
    summary = { Text("手动提交脱敏样本，帮助改进本地规则和模型") },
    icon = { Icon(Icons.Rounded.Forum, contentDescription = null) },
    onClick = onNavigateToSampleSubmission
)
```

- [ ] **Step 6: Run compile check**

Run:

```bash
cd "/Users/mystery0/StudioProjects/PixelText" && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/vip/mystery0/pixel/text/data/repository/SampleSubmissionRepository.kt \
  app/src/main/java/vip/mystery0/pixel/text/viewmodel/SampleSubmissionViewModel.kt \
  app/src/main/java/vip/mystery0/pixel/text/ui/screen/SampleSubmissionScreen.kt \
  app/src/main/java/vip/mystery0/pixel/text/di/AppModule.kt \
  app/src/main/java/vip/mystery0/pixel/text/ui/AppNavigation.kt \
  app/src/main/java/vip/mystery0/pixel/text/ui/screen/SettingsScreen.kt
git commit -m "feat: add desensitized sample submission"
```

## Task 10: Update Privacy Documentation

**Files:**
- Modify: `PRIVACY.md`
- Test: manual review

- [ ] **Step 1: Update upload disclosure**

Add a section under `数据是否会上传或共享`:

```markdown
当你主动使用“贡献脱敏短信样本”功能时，本应用会把你在提交页面中输入的脱敏短信样本、可选发件方、样本类别、应用版本、规则版本、模型版本，以及 Android 系统生成的 `ANDROID_ID` 上传到 PixelText 服务端。该上传行为只在你主动点击提交后发生。本应用不会自动上传短信内容。

`ANDROID_ID` 用于反滥用、限流、封禁垃圾提交和样本质量风控。服务端不会把该设备标识写入训练数据集，也不会把它用于规则或模型产物。
```

- [ ] **Step 2: Update permission/network wording**

Under `INTERNET` permission description, include:

```markdown
还用于你主动检查规则/模型更新、下载规则/模型资源，以及主动提交脱敏样本。
```

- [ ] **Step 3: Manual review**

Read the changed `PRIVACY.md` and confirm it still states:

- No automatic SMS upload
- User controls sample submission
- Sample text should be desensitized by the user
- `ANDROID_ID` is uploaded only for risk control during sample submission

- [ ] **Step 4: Commit**

```bash
git add PRIVACY.md
git commit -m "docs: update privacy policy for hub features"
```

## Task 11: Final Verification

**Files:**
- Verify all files modified by Tasks 1-10

- [ ] **Step 1: Compile**

Run:

```bash
cd "/Users/mystery0/StudioProjects/PixelText" && ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Lint**

Run:

```bash
cd "/Users/mystery0/StudioProjects/PixelText" && ./gradlew :app:lintDebug
```

Expected: task completes without new Hub-related lint errors.

- [ ] **Step 3: Manual smoke test**

On a debug build:

- Open Settings.
- Confirm rules/model version rows show `builtin` on first launch.
- Tap `检查资源更新`; with an unreachable dev server, confirm a user-facing error is shown.
- Open `贡献脱敏短信样本`.
- Leave consent unchecked and confirm submit is disabled.
- Enter a desensitized sample, check consent, submit against a dev server, and confirm success message.
- If a test manifest is available, update resources and restart the app; confirm message parsing and spam classifier still work.

- [ ] **Step 4: Commit verification fixes**

If verification required any fixes, commit only those fixes:

```bash
git add app PRIVACY.md
git commit -m "fix: stabilize hub app integration"
```

