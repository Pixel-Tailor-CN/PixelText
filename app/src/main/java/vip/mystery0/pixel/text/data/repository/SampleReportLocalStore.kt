package vip.mystery0.pixel.text.data.repository

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.source.SampleReportBody
import vip.mystery0.pixel.text.data.source.SampleReportDraftDto
import vip.mystery0.pixel.text.data.source.toDto
import vip.mystery0.pixel.text.data.source.toBody
import vip.mystery0.pixel.text.domain.hub.*
import vip.mystery0.pixel.text.util.AppStrings
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom
import java.util.UUID

/** 安装凭证、已确认请求和显式保留草稿都不进入系统迁移或应用手动备份。 */
class SampleReportLocalStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "sample-reports")
    private val mutex = Mutex()
    private val identityFile = AtomicFile(File(directory, "installation-id"))
    private val pendingFile = AtomicFile(File(directory, "pending.json"))
    private val draftFile = AtomicFile(File(directory, "draft.json"))
    private val moshi = Moshi.Builder().build()
    private val requestAdapter = moshi.adapter(SampleReportBody::class.java)
    private val draftAdapter = moshi.adapter(SampleReportDraftDto::class.java)

    suspend fun installationId(): String = locked {
        val stored = read(identityFile)
        if (stored != null) {
            if (!validIdentity(stored)) corrupt(AppStrings.get(R.string.domain_sample_report_name_identity))
            stored
        } else {
            // 身份遗失而请求仍在时不能创建另一身份，避免破坏原请求归属。
            if (read(pendingFile) != null) corrupt(AppStrings.get(R.string.domain_sample_report_name_identity))
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                .also { write(identityFile, it) }
        }
    }

    suspend fun pending(): SampleReportRequest? = locked { readPending() }

    /** 在同一临界区拒绝覆盖未确认请求，网络只能使用本方法返回的固定负载。 */
    suspend fun confirm(request: SampleReportRequest): SampleReportRequest = locked {
        readPending()?.let { return@locked it }
        write(pendingFile, requestAdapter.toJson(request.toBody()))
        request
    }

    suspend fun clearPending(requestId: String) = locked {
        if (readPending()?.clientRequestId == requestId) {
            pendingFile.delete()
            if (read(pendingFile) != null) {
                throw SampleReportException("LOCAL_WRITE", AppStrings.get(R.string.domain_sample_report_error_clear_pending))
            }
        }
    }

    suspend fun draft(): SampleReportDraft? = locked {
        val text = read(draftFile) ?: return@locked null
        val dto = try { draftAdapter.fromJson(text) } catch (_: Exception) {
            corrupt(AppStrings.get(R.string.domain_sample_report_name_draft))
        } ?: corrupt(AppStrings.get(R.string.domain_sample_report_name_draft))
        SampleReportDraft(dto.content, dto.sender, dto.category, dto.learningFeedback?.toDomain())
    }

    suspend fun saveDraft(draft: SampleReportDraft) = locked {
        write(draftFile, draftAdapter.toJson(SampleReportDraftDto(draft.content, draft.sender, draft.category, draft.learningFeedback?.toDto())))
    }

    suspend fun clearDraft() = locked {
        draftFile.delete()
        if (read(draftFile) != null) {
            throw SampleReportException("LOCAL_WRITE", AppStrings.get(R.string.domain_sample_report_error_clear_draft))
        }
    }

    private fun readPending(): SampleReportRequest? {
        val text = read(pendingFile) ?: return null
        val pendingName = AppStrings.get(R.string.domain_sample_report_name_pending)
        val body = try { requestAdapter.fromJson(text) } catch (_: Exception) { corrupt(pendingName) }
            ?: corrupt(pendingName)
        val identity = read(identityFile)
        if (identity != body.installationId ||
            !validIdentity(body.installationId) ||
            !body.androidId.matches(Regex("[a-fA-F0-9]{16}")) ||
            runCatching { UUID.fromString(body.clientRequestId).toString() }.getOrNull() != body.clientRequestId ||
            SampleSubmissionRepository.validationError(body.content, body.sender, body.category,
                listOf(body.appVersion, body.ruleVersion, body.modelVersion)) != null
        ) corrupt(pendingName)
        return body.toDomain()
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun read(file: AtomicFile): String? = try {
        file.openRead().use {
            if (it.channel.size() > 512 * 1024) corrupt(AppStrings.get(R.string.domain_sample_report_name_local_data))
            it.readBytes().toString(Charsets.UTF_8)
        }
    } catch (_: FileNotFoundException) {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            throw SampleReportException("LOCAL_READ", AppStrings.get(R.string.domain_sample_report_error_read_local))
        }
        null
    }

    private fun write(file: AtomicFile, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > 512 * 1024) {
            throw SampleReportException("LOCAL_SIZE", AppStrings.get(R.string.domain_sample_report_error_draft_too_large))
        }
        val writeError = AppStrings.get(R.string.domain_sample_report_error_write_local)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw SampleReportException("LOCAL_WRITE", writeError)
        }
        val stream = try { file.startWrite() } catch (_: Exception) {
            throw SampleReportException("LOCAL_WRITE", writeError)
        }
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
            // AtomicFile 的最终重命名失败可能只记录系统日志，因此额外核对实际落盘内容。
            if (read(file) != value) throw IllegalStateException("atomic write verification failed")
        } catch (_: Exception) {
            file.failWrite(stream)
            throw SampleReportException("LOCAL_WRITE", writeError)
        }
    }

    private fun corrupt(name: String): Nothing = throw SampleReportException(
        "LOCAL_CORRUPT",
        AppStrings.get(R.string.domain_sample_report_error_corrupt, name),
    )

    private fun validIdentity(value: String): Boolean = try {
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        value.matches(Regex("[A-Za-z0-9_-]{43}")) && Base64.decode(value, flags).size == 32 &&
            Base64.encodeToString(Base64.decode(value, flags), flags) == value
    } catch (_: IllegalArgumentException) { false }
}
