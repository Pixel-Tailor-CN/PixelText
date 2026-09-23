package vip.mystery0.pixel.text.data.repository.mms

import android.util.Log
import java.io.IOException
import vip.mystery0.pixel.text.domain.model.InitializationBatchResult
import vip.mystery0.pixel.text.data.repository.mirror.MirrorAttachmentCopier
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.room.withTransaction
import vip.mystery0.pixel.text.data.db.mirror.*
import vip.mystery0.pixel.text.domain.model.mirror.*
import vip.mystery0.pixel.text.domain.model.mms.MmsContentKind
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
import vip.mystery0.pixel.text.domain.repository.MmsContentRepository

private const val TAG = "MmsTextIndexer"

const val MMS_TEXT_INDEX_VERSION = 3

/** 版本及全部输入身份参与失效，附件 READY 内部更新也不能复用旧文本。 */
suspend fun mmsContentFingerprint(snapshot: MirrorMessageModel): String {
    val digest = MessageDigest.getInstance("SHA-256")
    suspend fun add(value: String?) {
        digest.update(if (value == null) 0.toByte() else 1.toByte())
        if (value == null) return
        for (shift in listOf(24, 16, 8, 0)) digest.update((value.length ushr shift).toByte())
        value.forEachIndexed { index, char ->
            if (index % 4096 == 0) currentCoroutineContext().ensureActive()
            digest.update((char.code ushr 8).toByte())
            digest.update(char.code.toByte())
        }
    }
    add(MMS_TEXT_INDEX_VERSION.toString())
    add(snapshot.revision.toString())
    add(snapshot.subject); add(snapshot.decodedSubject)
    add(snapshot.pduType.toString()); add(snapshot.structureComplete.toString())
    snapshot.parts.forEach { part ->
        add(part.sourceId.toString()); add(part.sequence.toString()); add(part.mimeType)
        add(part.charset.toString()); add(part.filename); add(part.name)
        add(part.contentId); add(part.contentLocation); add(part.text)
        add(part.attachment?.state?.name); add(part.attachment?.localUri)
        add(part.attachment?.sha256); add(part.attachment?.byteCount.toString())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}


fun mmsAttachmentSummary(kinds: List<MmsContentKind>): String {
    val leaves = kinds.filter { it != MmsContentKind.SMIL && it != MmsContentKind.MULTIPART }
    if (leaves.isEmpty()) return "彩信（暂无可读内容）"
    val labels = leaves.groupingBy { kind -> when (kind) {
        MmsContentKind.IMAGE -> "图片"
        MmsContentKind.AUDIO -> "音频"
        MmsContentKind.VIDEO -> "视频"
        MmsContentKind.CONTACT -> "联系人"
        MmsContentKind.CALENDAR -> "日历"
        MmsContentKind.HTML -> "网页"
        MmsContentKind.TEXT -> "文本"
        else -> "文件"
    } }.eachCount()
    return labels.entries.joinToString("、") { (label, count) -> if (count == 1) label else "$label × $count" }
}

/** 应用级单个增量任务；搜索输入只读取已派生文本，不启动解码。 */
class MmsTextIndexer(
    private val mirror: MessageMirrorRepository,
    private val contents: MmsContentRepository,
    private val database: MessageMirrorDatabase,
    private val copier: MirrorAttachmentCopier,
    private val synchronizer: vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var previousInputs: List<Pair<SourceMessageKey, String>>? = null
            var previousMissing = emptySet<SourceMessageKey>()
            mirror.observeAllMessages().map { rows -> rows.filter { it.key.transport == MessageTransport.MMS }
                .map { snapshot -> ensureActive(); snapshot to mmsContentFingerprint(snapshot) } }
                .transform { rows ->
                    val inputs = rows.map { it.first.key to it.second }
                    val missing = rows.filter { it.first.mmsSummary == null }.mapTo(mutableSetOf()) { it.first.key }
                    // 每次记录缺失集合，但自己的成功写入只减少缺失，不重启正在进行的解析。
                    // 已存在的派生行后来丢失/版本失效时，即使消息指纹不变也重新生成。
                    val changed = inputs != previousInputs || (missing - previousMissing).isNotEmpty()
                    previousInputs = inputs
                    previousMissing = missing
                    if (changed) emit(rows)
                }
                .collectLatest { rows ->
                    for ((snapshot, fingerprint) in rows) {
                        ensureActive()
                        try {
                            indexSnapshot(snapshot, fingerprint, strict = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.w(
                                TAG,
                                "index mms text failed message_id=${snapshot.key.sourceId} error=${e.javaClass.simpleName}"
                            )
                        }
                    }
                }
        }
    }

    /** 有限本地批次，可等待实际写入；不会触发网络下载或吞掉初始化失败。 */
    suspend fun indexInitializationBatch(
        afterLocalId: Long,
        upperLocalId: Long,
        limit: Int = 100,
    ): InitializationBatchResult = withContext(Dispatchers.IO) {
        require(limit > 0)
        val dao = database.mirrorDao()
        val rows = dao.initializationBatch("MMS", afterLocalId, upperLocalId, limit)
        var checkpoint = afterLocalId
        var unavailable = 0
        for (row in rows) {
            ensureActive()
            if (!row.message.structureComplete) {
                synchronizer.refreshInitializationMessage(
                    SourceMessageKey(
                        MessageTransport.MMS,
                        row.message.sourceId
                    )
                )
                // 新子结构可能新增附件，下一批必须重读后再复制。
                return@withContext InitializationBatchResult(
                    checkpoint,
                    complete = false,
                    unavailableCount = unavailable
                )
            }
            // 取消/进程死亡后的 COPYING 由既有恢复逻辑释放；活跃复制不会被重置。
            if (row.attachments.any { it.state == "COPYING" }) copier.recover()
            for (attachment in row.attachments) {
                if (attachment.state in setOf("SOURCE_PRESENT", "COPY_FAILED", "COPYING") ||
                    attachment.state == "SOURCE_UNREADABLE" && attachment.error != "source_unreadable"
                ) {
                    copier.copyPart(row.message.localId, row.message.revision, attachment.partId)
                }
            }
            val latestRecord = dao.getLocal(row.message.localId)
            if (latestRecord != null) {
                if (!latestRecord.message.structureComplete) throw IOException("mms_structure_incomplete")
                latestRecord.attachments.forEach { attachment ->
                    when {
                        attachment.error == "permission_denied" -> throw SecurityException("mms_attachment_permission")
                        attachment.state in setOf("SOURCE_PRESENT", "COPY_FAILED", "COPYING") ->
                            throw IOException("mms_attachment_not_ready")

                        attachment.state == "SOURCE_UNREADABLE" -> unavailable++
                    }
                }
                val key = SourceMessageKey(MessageTransport.MMS, row.message.sourceId)
                val snapshot = mirror.getMessage(key)
                if (snapshot != null && !indexSnapshot(
                        snapshot,
                        mmsContentFingerprint(snapshot),
                        strict = true
                    )
                ) {
                    return@withContext InitializationBatchResult(
                        checkpoint,
                        complete = false,
                        unavailableCount = unavailable
                    )
                }
            }
            checkpoint = row.message.localId
        }
        InitializationBatchResult(
            checkpoint,
            complete = rows.size < limit,
            unavailableCount = unavailable
        )
    }

    private suspend fun indexSnapshot(
        snapshot: MirrorMessageModel,
        fingerprint: String,
        strict: Boolean
    ): Boolean {
        val dao = database.mirrorDao()
        val existing = dao.getMmsText(snapshot.localId)
        // 初始化必须确认旧的不可搜索索引不是曾被吞掉的临时解析失败。
        if (existing?.let { it.version == MMS_TEXT_INDEX_VERSION && it.fingerprint == fingerprint && (!strict || it.searchReady) } == true) return true
        val model = contents.read(snapshot.key) ?: return mirror.getMessage(snapshot.key) == null
        if (strict && (model.preparing || model.parts.any {
                it.issue in setOf(
                    "read_failed",
                    "copy_failed",
                    "copying",
                    "source_present"
                )
            })) {
            (contents as? MmsContentRepositoryImpl)?.invalidate(snapshot.key)
            throw IOException("mms_text_not_ready")
        }
        return database.withTransaction {
            val latest = mirror.getMessage(snapshot.key)
            if (latest == null) return@withTransaction true
            if (mmsContentFingerprint(latest) != fingerprint) return@withTransaction false
            dao.putMmsText(
                MmsTextIndexEntity(
                    localId = snapshot.localId,
                    version = MMS_TEXT_INDEX_VERSION,
                    fingerprint = fingerprint,
                    summary = model.summary,
                    searchableText = model.searchableText,
                    searchBody = model.searchBody,
                    searchReady = model.searchReady,
                    sourceRevision = latest.revision,
                )
            )
            true
        }
    }
}
