package vip.mystery0.pixel.text.data.repository

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.source.SampleReportBody
import vip.mystery0.pixel.text.data.source.SampleReportDraftDto
import vip.mystery0.pixel.text.data.source.toBody
import vip.mystery0.pixel.text.domain.hub.*
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
            if (!validIdentity(stored)) corrupt("安装身份")
            stored
        } else {
            // 身份遗失而请求仍在时不能创建另一身份，避免破坏原请求归属。
            if (read(pendingFile) != null) corrupt("安装身份")
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
            if (read(pendingFile) != null) throw SampleReportException("LOCAL_WRITE", "无法清除本机待确认请求，请重试")
        }
    }

    suspend fun draft(): SampleReportDraft? = locked {
        val text = read(draftFile) ?: return@locked null
        val dto = try { draftAdapter.fromJson(text) } catch (_: Exception) { corrupt("保留草稿") }
            ?: corrupt("保留草稿")
        SampleReportDraft(dto.content, dto.sender, dto.category)
    }

    suspend fun saveDraft(draft: SampleReportDraft) = locked {
        write(draftFile, draftAdapter.toJson(SampleReportDraftDto(draft.content, draft.sender, draft.category)))
    }

    suspend fun clearDraft() = locked {
        draftFile.delete()
        if (read(draftFile) != null) throw SampleReportException("LOCAL_WRITE", "无法清除本机草稿，请重试")
    }

    private fun readPending(): SampleReportRequest? {
        val text = read(pendingFile) ?: return null
        val body = try { requestAdapter.fromJson(text) } catch (_: Exception) { corrupt("待确认请求") }
            ?: corrupt("待确认请求")
        val identity = read(identityFile)
        if (identity != body.installationId ||
            !validIdentity(body.installationId) ||
            !body.androidId.matches(Regex("[a-fA-F0-9]{16}")) ||
            runCatching { UUID.fromString(body.clientRequestId).toString() }.getOrNull() != body.clientRequestId ||
            SampleSubmissionRepository.validationError(body.content, body.sender, body.category,
                listOf(body.appVersion, body.ruleVersion, body.modelVersion)) != null
        ) corrupt("待确认请求")
        return body.toDomain()
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun read(file: AtomicFile): String? = try {
        file.openRead().use {
            if (it.channel.size() > 512 * 1024) corrupt("本机上报数据")
            it.readBytes().toString(Charsets.UTF_8)
        }
    } catch (error: FileNotFoundException) {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            throw SampleReportException("LOCAL_READ", "无法读取本机上报数据，请检查存储后重试")
        }
        null
    }

    private fun write(file: AtomicFile, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > 512 * 1024) throw SampleReportException("LOCAL_SIZE", "本机草稿过大，请减少内容后再保留")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw SampleReportException("LOCAL_WRITE", "无法保存本机上报数据，请检查存储后重试")
        }
        val stream = try { file.startWrite() } catch (_: Exception) {
            throw SampleReportException("LOCAL_WRITE", "无法保存本机上报数据，请检查存储后重试")
        }
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
            // AtomicFile 的最终重命名失败可能只记录系统日志，因此额外核对实际落盘内容。
            if (read(file) != value) throw IllegalStateException("atomic write verification failed")
        } catch (_: Exception) {
            file.failWrite(stream)
            throw SampleReportException("LOCAL_WRITE", "无法保存本机上报数据，请检查存储后重试")
        }
    }

    private fun corrupt(name: String): Nothing = throw SampleReportException("LOCAL_CORRUPT",
        "${name}损坏，已保留文件。请检查存储或恢复本次安装的数据后重试；不会自动更换身份或请求")

    private fun validIdentity(value: String): Boolean = try {
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        value.matches(Regex("[A-Za-z0-9_-]{43}")) && Base64.decode(value, flags).size == 32 &&
            Base64.encodeToString(Base64.decode(value, flags), flags) == value
    } catch (_: IllegalArgumentException) { false }
}
