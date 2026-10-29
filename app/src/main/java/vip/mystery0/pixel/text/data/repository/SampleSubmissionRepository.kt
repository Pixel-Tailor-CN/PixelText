package vip.mystery0.pixel.text.data.repository

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.data.source.PixelTextHubClient
import vip.mystery0.pixel.text.domain.hub.*
import vip.mystery0.pixel.text.domain.sample.SampleCategory
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import java.util.UUID

class SampleSubmissionRepository(
    private val context: Context,
    private val client: PixelTextHubClient,
    private val settings: AppSettingsRepository,
    private val localStore: SampleReportLocalStore,
) {
    private val submitMutex = Mutex()
    private var submitRetryAt = 0L

    suspend fun identity(): SampleReportIdentity {
        val id = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        } catch (_: SecurityException) { "" }
        return SampleReportIdentity(id.takeIf { it.matches(Regex("[a-fA-F0-9]{16}")) }.orEmpty(),
            localStore.installationId())
    }

    suspend fun pending(): SampleReportRequest? = localStore.pending()
    suspend fun draft(): SampleReportDraft? = localStore.draft()
    suspend fun saveDraft(draft: SampleReportDraft) = localStore.saveDraft(draft)
    suspend fun clearDraft() = localStore.clearDraft()
    suspend fun list(cursor: String? = null): SampleReportPage = client.listReports(identity(), cursor)
    suspend fun detail(id: String): SampleReportDetail = client.getReport(identity(), id)

    suspend fun submit(content: String, sender: String, category: String,
        onPersisted: (SampleReportRequest) -> Unit): SampleReportSubmission =
        submitMutex.withLock {
            // 其他页面已持久化的请求优先恢复，不能把另一份表单覆盖进去。
            if (localStore.pending() != null) throw SampleReportException("PENDING_EXISTS", "有一条提交尚未确认，请先恢复本次提交")
            val versions = listOf(BuildConfig.VERSION_NAME, settings.getRuleResourceVersion(), settings.getSpamModelResourceVersion())
            validationError(content, sender, category, versions)?.let { throw SampleReportException("VALIDATION", it) }
            val identity = identity()
            if (identity.androidId.isEmpty()) throw SampleReportException("DEVICE_ID", "无法读取有效的设备标识，暂不能提交")
            checkSubmitCooldown()
            val request = localStore.confirm(SampleReportRequest(identity.androidId, identity.installationId,
                UUID.randomUUID().toString(), content.trim(), sender.trim(), category,
                versions[0], versions[1], versions[2]))
            // 只有完整负载原子落盘并回读成功，界面才能解除准备阶段的返回保护。
            onPersisted(request)
            sendConfirmed(request)
        }

    suspend fun retryPending(onPersisted: (SampleReportRequest) -> Unit): SampleReportSubmission = submitMutex.withLock {
        val request = localStore.pending() ?: throw SampleReportException("NO_PENDING", "没有待确认的提交，请返回重新打开页面")
        onPersisted(request)
        checkSubmitCooldown()
        // 列表不含 clientRequestId，摘要相似不能证明成功；仅幂等提交响应可结束该请求。
        try {
            client.listReports(SampleReportIdentity(request.androidId, request.installationId), null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SampleReportException) {
            if (error.retryAfterSeconds != null) throw error
        } catch (_: Exception) {
            // 用户已明确点击重试，查询失败仍可用完全相同负载取得幂等响应。
        }
        sendConfirmed(request)
    }

    private suspend fun sendConfirmed(request: SampleReportRequest): SampleReportSubmission {
        try {
            val result = client.submitReport(request)
            if (!isValidSampleReportId(result.report.summary.id)) {
                throw SampleReportException("INVALID_RESPONSE", "服务响应不完整，已保留本次提交")
            }
            withContext(NonCancellable) {
                // 草稿清理失败不影响已确认响应；请求清理放在最后，失败时仍可幂等恢复。
                try { localStore.clearDraft() } catch (_: Exception) { }
                localStore.clearPending(request.clientRequestId)
            }
            return result
        } catch (error: CancellationException) {
            // HTTP 可能已到达服务端，取消绝不能删除固定负载。
            throw error
        } catch (error: SampleReportException) {
            error.retryAfterSeconds?.let { submitRetryAt = System.currentTimeMillis() + it.coerceAtMost(86400) * 1000 }
            if (error.definitelyNotAccepted) withContext(NonCancellable) {
                localStore.clearPending(request.clientRequestId)
            }
            throw error
        }
    }

    private fun checkSubmitCooldown() {
        val remaining = (submitRetryAt - System.currentTimeMillis() + 999) / 1000
        if (remaining > 0) throw SampleReportException("RATE_LIMITED", "请求过于频繁，请稍后重试", remaining)
    }

    companion object {
        fun validationError(content: String, sender: String, category: String, versions: List<String> = emptyList()): String? {
            val text = content.trim()
            return when {
                text.codePointCount(0, text.length) < 6 -> "样本文本至少需要 6 个字符"
                text.toByteArray(Charsets.UTF_8).size > 64 * 1024 -> "样本文本不能超过 64 KiB，请减少内容"
                sender.codePointCount(0, sender.length) > 256 -> "发送方不能超过 256 个字符"
                SampleCategory.fromValue(category) == null -> "请选择有效的样本类别"
                versions.any { it.codePointCount(0, it.length) > 128 } -> "资源版本信息过长，请更新资源后重试"
                else -> null
            }
        }
    }
}
