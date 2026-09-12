package vip.mystery0.pixel.text.data.repository.mms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.source.mms.MmsPartReader
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.model.mirror.MirrorMessageModel
import vip.mystery0.pixel.text.domain.model.mirror.MirrorPartModel
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.model.mms.MmsContentKind
import vip.mystery0.pixel.text.domain.model.mms.MmsContentModel
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.domain.model.mms.MmsPartKey
import vip.mystery0.pixel.text.domain.model.mms.usesInlineTextCopy
import vip.mystery0.pixel.text.domain.parser.mms.MmsMimeTypes
import vip.mystery0.pixel.text.domain.parser.mms.MmsMultipartResolver
import vip.mystery0.pixel.text.domain.parser.mms.MmsSmilParser
import vip.mystery0.pixel.text.domain.parser.mms.MmsHtmlParser
import vip.mystery0.pixel.text.domain.parser.mms.MmsContactParser
import vip.mystery0.pixel.text.domain.parser.mms.MmsCalendarParser
import vip.mystery0.pixel.text.mms.vendor.pdu.PduBody
import vip.mystery0.pixel.text.mms.vendor.pdu.PduParser
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
import vip.mystery0.pixel.text.domain.repository.MmsContentRepository

class MmsContentRepositoryImpl(
    private val mirror: MessageMirrorRepository,
    private val reader: MmsPartReader,
    private val htmlParser: MmsHtmlParser,
    private val contactParser: MmsContactParser,
    private val calendarParser: MmsCalendarParser,
) : MmsContentRepository {
    private data class Entry(val fingerprint: String, val model: MmsContentModel, val bytes: Long)

    private val cache = LinkedHashMap<SourceMessageKey, Entry>(16, 0.75f, true)
    private val cacheLock = Any()
    private var cachedBytes = 0L
    private var invalidationEpoch = 0L

    override fun observe(key: SourceMessageKey): Flow<MmsContentModel?> = channelFlow {
        try {
            mirror.observeMessage(key).collectLatest { snapshot ->
                if (snapshot == null || key.transport != MessageTransport.MMS) {
                    invalidate(key)
                    send(null)
                } else {
                    try {
                        val epoch = synchronized(cacheLock) { invalidationEpoch }
                        val fingerprint = mmsContentFingerprint(snapshot)
                        val cached = cached(key, fingerprint)
                        if (cached != null) {
                            send(cached)
                        } else {
                            send(prepare(snapshot))
                            send(parseAndCache(snapshot, fingerprint, epoch))
                        }
                    } catch (cancelled: CancellationException) {
                        invalidate(key)
                        throw cancelled
                    }
                }
            }
        } finally {
            // 订阅取消后不保留该消息的派生正文。
            invalidate(key)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun read(key: SourceMessageKey): MmsContentModel? = try {
        withContext(Dispatchers.IO) {
            val epoch = synchronized(cacheLock) { invalidationEpoch }
            val snapshot = mirror.getMessage(key)
            if (snapshot == null || key.transport != MessageTransport.MMS) {
                invalidate(key)
                return@withContext null
            }
            val fingerprint = mmsContentFingerprint(snapshot)
            cached(key, fingerprint) ?: parseAndCache(snapshot, fingerprint, epoch)
        }
    } catch (cancelled: CancellationException) {
        invalidate(key)
        throw cancelled
    }

    /** 删除同步回调也调用此方法，覆盖当前没有订阅者但曾单次读取的消息。 */
    fun invalidate(key: SourceMessageKey) = synchronized(cacheLock) {
        invalidationEpoch++
        cache.remove(key)?.let { cachedBytes -= it.bytes }
        Unit
    }

    private fun cached(key: SourceMessageKey, fingerprint: String): MmsContentModel? = synchronized(cacheLock) {
        cache[key]?.takeIf { it.fingerprint == fingerprint }?.model
    }

    private suspend fun parseAndCache(snapshot: MirrorMessageModel, fingerprint: String, epoch: Long): MmsContentModel {
        val budget = MmsPartReader.Budget()
        val decodedParts = orderedParts(snapshot).map { part ->
            currentCoroutineContext().ensureActive()
            val content = partContent(snapshot, part)
            if (MmsMimeTypes.isText(content.kind)) {
                val result = reader.readText(part, budget)
                content.copy(text = result.text, byteCount = result.byteCount, issue = result.issue)
            } else content
        }
        val restoredParts = restoreMultipart(snapshot, decodedParts, budget)
        val context = currentCoroutineContext()
        val htmlDocuments = restoredParts.filter { it.kind == MmsContentKind.HTML && it.text != null }.associate { part ->
            context.ensureActive()
            val document = htmlParser.prepare(part, restoredParts)
            context.ensureActive()
            part.key to document
        }
        val parts = restoredParts.map { part ->
            context.ensureActive()
            when {
                part.kind == MmsContentKind.HTML -> part.copy(
                    htmlSummary = htmlDocuments[part.key]?.plainText?.take(320),
                    issue = part.issue ?: htmlDocuments[part.key]?.issue,
                )
                part.kind == MmsContentKind.CONTACT && part.text != null -> part.copy(
                    contacts = contactParser.parse(part.text) { context.ensureActive() }.map { contact ->
                        val reference = contact.photoReference
                        if (reference == null) contact else {
                            val target = restoredParts.filter { candidate ->
                                candidate.kind == MmsContentKind.IMAGE && candidate.localUri != null &&
                                    candidate.byteCount?.let { it in 1..MmsContactParser.MAX_PHOTO_BYTES.toLong() } == true &&
                                    if (reference.startsWith("cid:", true)) {
                                        candidate.contentId?.trim()?.removeSurrounding("<", ">") == reference.substring(4)
                                    } else candidate.contentLocation == reference
                            }.singleOrNull()
                            contact.copy(photoPartId = target?.key?.partId, photoReference = null,
                                importWarning = contact.importWarning ?: if (target == null) "名片照片无法在本消息内读取，请核对原件" else null)
                        }
                    },
                )
                part.kind == MmsContentKind.CALENDAR && part.text != null -> part.copy(
                    calendarEvents = calendarParser.parse(part.text) { context.ensureActive() },
                )
                else -> part
            }
        }
        val selection = MmsMultipartResolver().selectWithIssues(parts)
        val smilResults = parts.filter { it.kind == MmsContentKind.SMIL && it.text != null }.map {
            MmsSmilParser().parseWithIssues(it.text!!, parts)
        }
        // 多份控制文档没有可靠的主文档指示时不猜测，全部回退附件。
        val pages = if (smilResults.size == 1 && smilResults.single().issues.isEmpty()) smilResults.single().pages else emptyList()
        val issues = (selection.issues + smilResults.flatMap { it.issues } +
            (if (parts.any { it.kind == MmsContentKind.SMIL && it.text == null }) listOf("smil_unreadable") else emptyList()) +
            if (smilResults.size > 1) listOf("smil_ambiguous_document") else emptyList()).distinct()
        val referenced = pages.flatMap { it.partIds }.toSet()
        val subject = snapshot.decodedSubject ?: snapshot.subject
        val searchableText = buildString {
            subject?.takeIf { it.isNotBlank() }?.let { append(it) }
            selection.parts.forEach { part ->
                context.ensureActive()
                val text = when (part.kind) {
                    MmsContentKind.TEXT -> part.text
                    MmsContentKind.HTML -> htmlDocuments[part.key]?.plainText
                    MmsContentKind.CONTACT -> part.contacts.joinToString("\n") { contact ->
                        (listOfNotNull(contact.name, contact.organization, contact.title, contact.notes) +
                            (contact.phones + contact.emails + contact.addresses).map { it.value }).joinToString("\n")
                    }
                    MmsContentKind.CALENDAR -> part.calendarEvents.joinToString("\n") { event ->
                        listOfNotNull(event.title, event.location, event.description,
                            event.start?.toString(), event.end?.toString()).joinToString("\n")
                    }
                    else -> null
                }
                text?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append('\n')
                    append(it)
                }
            }
        }
        val pending = pendingDownload(snapshot)
        val model = MmsContentModel(
            key = snapshot.key, revision = snapshot.revision, subject = subject,
            parts = parts, pages = pages,
            summary = searchableText.takeIf { it.isNotBlank() }?.take(160)
                ?: if (pending) "等待下载彩信" else mmsAttachmentSummary(parts.map { it.kind }),
            searchableText = searchableText, pendingDownload = pending,
            bodyPartIds = selection.parts.map { it.key.partId },
            attachmentPartIds = parts.filter {
                it.kind !in setOf(MmsContentKind.SMIL, MmsContentKind.MULTIPART) && it.key.partId !in referenced
            }.map { it.key.partId },
            issues = issues,
        )
        currentCoroutineContext().ensureActive()
        val bytes = estimateBytes(model)
        synchronized(cacheLock) {
            // 删除或取消可能与单次读取并发，旧解析不能把已清除的正文重新放回缓存。
            if (epoch != invalidationEpoch) return@synchronized
            cache.remove(snapshot.key)?.let { cachedBytes -= it.bytes }
            if (bytes <= MAX_CACHE_BYTES) {
                cache[snapshot.key] = Entry(fingerprint, model, bytes)
                cachedBytes += bytes
                while (cache.size > MAX_CACHE_ENTRIES || cachedBytes > MAX_CACHE_BYTES) {
                    val iterator = cache.entries.iterator()
                    cachedBytes -= iterator.next().value.bytes
                    iterator.remove()
                }
            }
        }
        return model
    }

    /** Provider 不增加私有列；只从本地原容器与唯一的消息内标识恢复关系。 */
    private suspend fun restoreMultipart(
        snapshot: MirrorMessageModel, parts: List<MmsPartContent>, budget: MmsPartReader.Budget,
    ): List<MmsPartContent> {
        val sources = snapshot.parts.associateBy { it.sourceId }
        val relations = mutableMapOf<Long, List<Long>>()
        val problems = mutableMapOf<Long, String>()
        val parseBudget = PduParser.MultipartBudget()
        fun identity(value: String?) = value?.trim()?.removeSurrounding("<", ">")?.takeIf { it.isNotEmpty() }
        fun match(body: PduBody, parentId: Long, staged: MutableMap<Long, List<Long>>, ancestors: Set<Long>): Boolean {
            if (parentId in ancestors || ancestors.size > 8) return false
            val children = mutableListOf<Long>()
            for (index in 0 until body.partsNum) {
                val child = body.getPart(index)
                val cid = identity(child.contentId?.toString(Charsets.ISO_8859_1))
                val location = child.contentLocation?.toString(Charsets.ISO_8859_1)
                if (cid == null && location.isNullOrBlank()) return false
                val candidates = parts.filter {
                    (cid == null || identity(it.contentId) == cid) &&
                        (location == null || it.contentLocation == location)
                }
                val target = candidates.singleOrNull() ?: return false
                if (target.key.partId == parentId || target.key.partId in ancestors || target.key.partId in children ||
                    target.mimeType != MmsMimeTypes.normalize(child.contentType?.toString(Charsets.ISO_8859_1))) return false
                children += target.key.partId
                if (target.kind == MmsContentKind.MULTIPART) {
                    val nested = child.children ?: return false
                    if (!match(nested, target.key.partId, staged, ancestors + parentId)) return false
                }
            }
            staged[parentId] = children
            return true
        }
        for (container in parts.filter { it.kind == MmsContentKind.MULTIPART }) {
            currentCoroutineContext().ensureActive()
            val id = container.key.partId
            if (id in relations) continue
            val read = reader.readBytes(sources.getValue(id), budget)
            if (read.bytes == null) {
                problems[id] = read.issue ?: "multipart_unresolved"
                continue
            }
            val body = try { PduParser.parseMultipart(read.bytes, parseBudget) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { null }
            currentCoroutineContext().ensureActive()
            val staged = mutableMapOf<Long, List<Long>>()
            if (body == null) problems[id] = "multipart_invalid"
            else if (!match(body, id, staged, emptySet())) problems[id] = "multipart_ambiguous"
            else relations.putAll(staged)
        }
        val children = relations.values.flatten()
        if (children.distinct().size != children.size) {
            relations.keys.forEach { problems[it] = "multipart_ambiguous" }
            relations.clear()
        }
        return parts.map { part ->
            part.copy(
                childPartIds = relations[part.key.partId].orEmpty(),
                multipartResolved = part.key.partId in relations,
                issue = part.issue ?: problems[part.key.partId],
            )
        }
    }

    private fun prepare(snapshot: MirrorMessageModel) = MmsContentModel(
        key = snapshot.key, revision = snapshot.revision,
        subject = snapshot.decodedSubject ?: snapshot.subject,
        parts = orderedParts(snapshot).map { partContent(snapshot, it).copy(issue = "preparing") },
        pages = emptyList(), summary = "正在准备彩信内容", searchableText = "", preparing = true,
        pendingDownload = pendingDownload(snapshot),
    )

    private fun orderedParts(snapshot: MirrorMessageModel) = snapshot.parts.sortedWith(
        compareBy<MirrorPartModel> { it.sequence ?: Int.MAX_VALUE }.thenBy { it.sourceId },
    )

    private fun partContent(snapshot: MirrorMessageModel, part: MirrorPartModel): MmsPartContent {
        val kind = MmsMimeTypes.classify(part.mimeType)
        val hasInlineText = part.usesInlineTextCopy()
        return MmsPartContent(
            key = MmsPartKey(snapshot.key, part.sourceId), revision = snapshot.revision,
            kind = kind, mimeType = MmsMimeTypes.normalize(part.mimeType),
            displayName = listOf(part.filename, part.name, part.contentLocation)
                .firstOrNull { !it.isNullOrBlank() } ?: "附件 ${part.sourceId}",
            byteCount = if (hasInlineText) null else part.attachment?.byteCount,
            contentHash = if (hasInlineText) null else part.attachment?.sha256,
            inlineTextCopy = hasInlineText,
            state = if (hasInlineText) MirrorAttachmentState.READY else part.attachment?.state ?: MirrorAttachmentState.UNKNOWN,
            localUri = if (hasInlineText) null else part.attachment?.localUri, text = null,
            contentId = part.contentId, contentLocation = part.contentLocation,
            issue = if (hasInlineText) null else reader.issueFor(part),
        )
    }

    private fun pendingDownload(snapshot: MirrorMessageModel): Boolean =
        snapshot.pduType == 130 ||
            snapshot.parts.any { it.attachment?.state == MirrorAttachmentState.PENDING_DOWNLOAD }

    /** 不持有原始镜像；逐字符散列避免为超长内联文本再分配整份字节数组。 */
    /** 按 UTF-16 字符及对象开销保守估算，包含搜索文本的副本，不只统计原始字节。 */
    private fun estimateBytes(model: MmsContentModel): Long {
        fun size(value: String?) = if (value == null) 0L else 48L + value.length.toLong() * 2
        return 512L + size(model.subject) + size(model.summary) + size(model.searchableText) +
            24L * (model.bodyPartIds.size + model.attachmentPartIds.size) + model.issues.sumOf(::size) +
            model.pages.sumOf { 64L + 24L * it.partIds.size } +
            model.parts.sumOf { part ->
                256L + size(part.mimeType) + size(part.displayName) + size(part.localUri) +
                    size(part.htmlSummary) + size(part.contentHash) + size(part.text) + size(part.contentId) + size(part.contentLocation) + size(part.issue) +
                    24L * part.childPartIds.size + part.contacts.sumOf { contact ->
                        256L + size(contact.name) + size(contact.organization) + size(contact.title) + size(contact.notes) +
                            size(contact.importWarning) + (contact.photoBytes?.size ?: 0) +
                            (contact.phones + contact.emails + contact.addresses).sumOf { 64L + size(it.value) + size(it.label) }
                    } + part.calendarEvents.sumOf { event ->
                        256L + size(event.title) + size(event.location) + size(event.description) + size(event.recurrence) + size(event.importWarning)
                    }
            }
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 64
        const val MAX_CACHE_BYTES = 16L * 1024 * 1024
    }
}
