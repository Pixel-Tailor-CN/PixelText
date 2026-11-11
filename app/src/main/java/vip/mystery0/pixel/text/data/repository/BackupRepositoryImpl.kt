package vip.mystery0.pixel.text.data.repository

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import vip.mystery0.pixel.text.data.source.requireSmsAccess
import vip.mystery0.pixel.text.data.source.smsAccessState
import vip.mystery0.pixel.text.data.source.SmsAccessState
import vip.mystery0.pixel.text.data.source.SmsAccessException
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.data.backup.*
import vip.mystery0.pixel.text.data.db.mirror.MirrorSpamLegacyImporter
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.data.repository.mirror.RestoreSyncCoordinator
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler
import vip.mystery0.pixel.text.domain.backup.*
import vip.mystery0.pixel.text.domain.repository.VerificationCodeRepository
import vip.mystery0.pixel.text.domain.spam.KeywordSpamRepository
import java.io.File
import java.util.UUID

class BackupRepositoryImpl(
    private val context: Context,
    private val codec: BackupArchiveCodec,
    private val snapshots: AppDatabaseSnapshotter,
    private val reader: BackupDatabaseReader,
    private val settings: BackupSettingsMapper,
    private val rules: BackupRuleStore,
    private val sms: SmsRestoreDataSource,
    private val safety: RestoreSafetyCoordinator,
    private val synchronizer: MessageMirrorSynchronizer,
    private val appSettings: AppSettingsRepositoryImpl,
    private val verification: VerificationCodeRepository,
    private val keywords: KeywordSpamRepository,
    private val initializationGuard: vip.mystery0.pixel.text.data.repository.initialization.DataInitializationGuard,
    private val spamImporter: MirrorSpamLegacyImporter,
    private val restoreSync: RestoreSyncCoordinator,
    private val mirrorScheduler: MessageMirrorScheduler,
) : BackupRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var inspected: ValidatedBackup? = null
    private val root = File(context.noBackupFilesDir, "backup-staging")
    private val initialization = scope.async {
        if (root.exists()) backupRequire(root.deleteRecursively(), "无法清理上次操作的暂存数据")
        backupRequire(root.mkdirs() || root.isDirectory, "无法创建备份暂存目录")
    }
    private val mutable = MutableStateFlow(BackupOperationState(
        phase = if (safety.active) BackupPhase.INTERRUPTED else BackupPhase.IDLE,
        summary = safety.summary(), restoreProtected = safety.active,
        // 中断不是失败，由页面上的保护提示说明，避免同时出现两个相似提示。
        errorMessage = null,
    ))
    override val state: StateFlow<BackupOperationState> = mutable.asStateFlow()

    override fun exportTo(uri: String, sections: Set<BackupSection>, password: CharArray?) {
        val secret = password?.copyOf()
        password?.fill('\u0000')
        if (!start(BackupPhase.EXPORTING, secret, uri) {
            backupRequire(!safety.active, "请先确认结束上次恢复保护")
            backupRequire(sections.isNotEmpty(), "请选择备份内容")
            backupRequire(secret == null || secret.isNotEmpty(), "请输入加密密码后重试")
            discardPreview()
            val directory = newDirectory()
            var exported = false
            try {
                var syncedAt: Long? = null
                if (BackupSection.SMS in sections) {
                    context.requireSmsAccess()
                    phase(BackupPhase.SYNCING_MIRROR)
                    // 导入器也需要镜像同步锁，必须在快照锁之前完成旧放行状态迁移。
                    spamImporter.ensureImported()
                    synchronizer.withSmsBackupSnapshot { time ->
                        syncedAt = time
                        phase(BackupPhase.SNAPSHOTTING)
                        snapshots.capture(directory, sections)
                    }
                } else {
                    phase(BackupPhase.SNAPSHOTTING)
                    snapshots.capture(directory, sections)
                }
                if (BackupSection.SETTINGS in sections) { settings.export(directory); settings.validate(directory) }
                val count = smsCount(directory)
                val entries = mutableListOf<BackupEntry>()
                for (file in directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
                    entries += codec.describe(file, file.relativeTo(directory).invariantSeparatorsPath)
                }
                backupRequire(entries.sumOf { it.size } <= MAX_BACKUP_BYTES)
                val manifest = BackupManifest(appVersion = BuildConfig.VERSION_NAME, createdAt = System.currentTimeMillis(),
                    smsSyncedAt = syncedAt, sections = sections.toList(), smsCount = count, entries = entries)
                reader.validate(ValidatedBackup(directory, manifest))
                phase(BackupPhase.EXPORTING)
                codec.export(directory, manifest, uri, secret)
                exported = true
                mutable.update { it.copy(phase = BackupPhase.COMPLETED, processed = count, total = count) }
            } finally {
                directory.deleteRecursively()
                if (!exported) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri.toUri()) }
            }
        }) secret?.fill('\u0000')
    }

    override fun inspect(uri: String, password: CharArray?) {
        val secret = password?.copyOf()
        password?.fill('\u0000')
        if (!start(BackupPhase.VALIDATING, secret) {
            discardPreview()
            val directory = newDirectory()
            var keep = false
            try {
                val backup = codec.inspect(uri, secret, directory)
                reader.validate(backup)
                if (BackupSection.SETTINGS in backup.manifest.sections) settings.validate(directory)
                else backupRequire(!File(directory, "settings.json").exists() && !File(directory, "theme").exists())
                inspected = backup
                mutable.update { it.copy(phase = BackupPhase.PREVIEW, preview = BackupPreview(directory.name,
                    backup.manifest.sections.toSet(), backup.manifest.smsCount, backup.manifest.createdAt)) }
                keep = true
            } finally { if (!keep) directory.deleteRecursively() }
        }) secret?.fill('\u0000')
    }

    override fun restore(token: String, sections: Set<BackupSection>) {
        val preview = inspected
        start(BackupPhase.RESTORING) {
            var paused = false
            var finalReconciliationComplete = BackupSection.SMS !in sections
            try {
                restoreSync.acquireRestore()
                paused = true
                initializationGuard.withInputMutation {
                backupRequire(
                    preview != null && preview.directory.name == token,
                    "预览已过期，请重新选择文件"
                )
                val backup = requireNotNull(preview)
                backupRequire(
                    sections.isNotEmpty() && backup.manifest.sections.containsAll(sections),
                    "恢复类别无效"
                )
                if (BackupSection.SMS in sections) sms.requireAccess()
                // 所有包含历史数据/设置的恢复都建立保护，避免应用设置提前开启清理。
                safety.begin()
                var summary =
                    BackupSummary(remaining = if (BackupSection.SMS in sections) backup.manifest.smsCount else 0)
                publishSummary(summary)
                try {
                    if (BackupSection.RULES in sections) {
                        rules.restore(backup.directory)
                        summary =
                            summary.copy(completedSections = summary.completedSections + BackupSection.RULES)
                        publishSummary(summary)
                    }
                    if (BackupSection.SMS in sections) {
                        summary = sms.restore(backup.directory, summary, ::publishSummary)
                        summary =
                            summary.copy(completedSections = summary.completedSections + BackupSection.SMS)
                        publishSummary(summary)
                    }
                    if (BackupSection.SETTINGS in sections) {
                        settings.restore(backup.directory)
                        summary =
                            summary.copy(completedSections = summary.completedSections + BackupSection.SETTINGS)
                        publishSummary(summary)
                    }
                    if (hasReadPermission()) {
                        phase(BackupPhase.REBUILDING)
                        if (BackupSection.SMS in sections) synchronizer.withSmsBackupSnapshot { Unit }
                        keywords.rebuildAllMatches()
                        verification.rebuildAll()
                    }
                } finally {
                    discardPreview()
                }
                if (BackupSection.SMS in sections) {
                    phase(BackupPhase.REBUILDING)
                    finalReconciliationComplete = mirrorScheduler.reconcileAfterRestore()
                    if (!finalReconciliationComplete) {
                        throw SmsAccessException(SmsAccessState.READ_PERMISSION_REQUIRED)
                    }
                }
                // 恢复和最终对账都成功后自动结束保护；中断或失败时仍保留保护等待用户确认。
                finishProtection()
                mutable.update {
                    it.copy(
                        phase = BackupPhase.COMPLETED,
                        preview = null,
                        restoreProtected = false
                    )
                }
                }
            } finally {
                if (paused) {
                    restoreSync.releaseRestore()
                    if (!finalReconciliationComplete) {
                        mirrorScheduler.schedule(forceReconcile = true, reason = "restore_finished")
                    }
                }
            }
        }
    }

    override fun cancel() {
        synchronized(this) {
            if (job?.isActive == true) job?.cancel()
            else { discardPreview(); mutable.update { it.copy(phase = if (safety.active) BackupPhase.INTERRUPTED else BackupPhase.IDLE, preview = null) } }
        }
    }

    override fun acknowledgeResult() {
        start(BackupPhase.REBUILDING) {
            finishProtection()
            discardPreview()
            mutable.value = BackupOperationState()
        }
    }

    override fun dismissResult() {
        discardPreview()
        mutable.value = BackupOperationState()
    }

    /** 结束保护时总会先暂停验证码自动删除，避免清理刚恢复的历史验证码；需要时可在设置中重新开启。 */
    private suspend fun finishProtection() {
        backupRequire(appSettings.restorePortablePreferences(listOf(
            PortablePreference("verification_code_auto_delete_enabled", "boolean", "false"))), "无法暂停验证码清理")
        safety.finish()
    }

    @Synchronized private fun start(phase: BackupPhase, secret: CharArray? = null, failedOutputUri: String? = null, action: suspend () -> Unit): Boolean {
        if (job?.isActive == true) return false
        mutable.update { BackupOperationState(phase = phase, summary = if (safety.active) it.summary else BackupSummary(), restoreProtected = safety.active) }
        job = scope.launch {
            try {
                initialization.await()
                action()
            } catch (_: CancellationException) {
                mutable.update { it.copy(phase = BackupPhase.INTERRUPTED, restoreProtected = safety.active,
                    errorMessage = if (phase == BackupPhase.EXPORTING) {
                        "操作已停止，请检查已保存的备份文件是否完整"
                    } else {
                        "操作已停止，已写入的短信会保留，重新导入会自动去重"
                    }) }
            } catch (error: Exception) {
                // 不显示底层异常文本，避免数据库/URI/正文进入界面或日志。
                val message = when (error) {
                    is SmsAccessException -> error.state.userMessage
                    is BackupException -> error.message
                    is SecurityException -> if (context.smsAccessState() != SmsAccessState.READY) {
                        context.smsAccessState().userMessage
                    } else "无法访问所选文件，请重新选择文件后重试"
                    is net.lingala.zip4j.exception.ZipException -> "密码错误或备份文件损坏"
                    else -> "操作失败（${error.javaClass.simpleName}），已写入的数据会保留，请检查权限、文件和空间"
                }
                mutable.update { it.copy(phase = BackupPhase.FAILED, restoreProtected = safety.active, errorMessage = message,
                    authorizationRequired = error is SmsAccessException ||
                        (error is SecurityException && context.smsAccessState() != SmsAccessState.READY)) }
            } finally {
                secret?.fill('\u0000')
                if (failedOutputUri != null && mutable.value.phase != BackupPhase.COMPLETED) runCatching {
                    android.provider.DocumentsContract.deleteDocument(context.contentResolver, failedOutputUri.toUri())
                }
            }
        }
        return true
    }
    private fun phase(value: BackupPhase) { mutable.update { it.copy(phase = value, restoreProtected = safety.active) } }
    private fun publishSummary(summary: BackupSummary) {
        mutable.update { it.copy(summary = summary, processed = summary.inserted + summary.existing + summary.failed,
            total = summary.inserted + summary.existing + summary.failed + summary.remaining, restoreProtected = safety.active) }
        safety.record(summary)
    }
    private fun newDirectory(): File = File(root, UUID.randomUUID().toString()).also { backupRequire(it.mkdirs(), "无法创建暂存目录") }
    private fun discardPreview() { inspected?.directory?.deleteRecursively(); inspected = null }
    private fun hasReadPermission() = context.smsAccessState() == SmsAccessState.READY
    private fun smsCount(directory: File): Long {
        val file = File(directory, "databases/message_mirror.db")
        if (!file.exists()) return 0
        return reader.open(file).use { db -> db.rawQuery("SELECT COUNT(*) FROM mirror_message", null).use { it.moveToFirst(); it.getLong(0) } }
    }
}
