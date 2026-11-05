package vip.mystery0.pixel.text.data.db.mirror

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.spam.MessageSpamSource

/** 只投影标记和版本；列表读取不加载正文、不重新判定规则。 */
data class MirrorSpamStateRow(
    val localId: Long,
    val messageId: Long,
    val messageRevision: Long,
    val threadId: Long?,
    val isSpam: Boolean?,
    val spamSource: Int,
    val spamScore: Float?,
    val spamModelIdentity: String?,
    val spamModelRevision: Long?,
    val spamModelGeneration: Long?,
    val spamModelManual: Boolean,
    val spamKeywordId: Long?,
    val spamAllowedSource: Int?,
    val spamCorrection: Boolean?,
) {
    val effectiveScore: Float?
        get() = when {
            spamSource == MessageSpamSource.MODEL -> spamScore
            isSpam == true -> 1f
            isSpam == false -> 0f
            else -> null
        }

    internal fun resolved(): MirrorSpamStateRow = when {
        spamAllowedSource != null -> copy(isSpam = false, spamSource = spamAllowedSource)
        spamKeywordId != null -> copy(isSpam = true, spamSource = MessageSpamSource.KEYWORD)
        spamCorrection != null -> copy(isSpam = spamCorrection, spamSource = MessageSpamSource.CORRECTION)
        spamScore != null -> copy(isSpam = spamScore >= MessageSpamSource.THRESHOLD, spamSource = MessageSpamSource.MODEL)
        else -> copy(isSpam = null, spamSource = MessageSpamSource.UNKNOWN)
    }

    internal fun update() = MirrorSpamUpdate(
        localId, isSpam, spamSource, spamScore, spamModelIdentity, spamModelRevision,
        spamModelGeneration, spamModelManual, spamKeywordId, spamAllowedSource, spamCorrection,
    )
}

/** 部分实体更新，避免并发写入覆盖已读、会话和同步代次等字段。 */
data class MirrorSpamUpdate(
    val localId: Long,
    val isSpam: Boolean?,
    val spamSource: Int,
    val spamScore: Float?,
    val spamModelIdentity: String?,
    val spamModelRevision: Long?,
    val spamModelGeneration: Long?,
    val spamModelManual: Boolean,
    val spamKeywordId: Long?,
    val spamAllowedSource: Int?,
    val spamCorrection: Boolean?,
)

private const val SPAM_COLUMNS = "localId, CASE WHEN transport='MMS' THEN -sourceId ELSE sourceId END AS messageId, " +
    "revision AS messageRevision, threadId, isSpam, spamSource, spamScore, spamModelIdentity, spamModelRevision, " +
    "spamModelGeneration, spamModelManual, spamKeywordId, spamAllowedSource, spamCorrection"

@Dao
abstract class MirrorSpamDao {
    @Query("SELECT " + SPAM_COLUMNS + " FROM mirror_message WHERE (transport='SMS' AND sourceId IN (:smsIds)) OR (transport='MMS' AND sourceId IN (:mmsIds))")
    abstract suspend fun rowsBySource(smsIds: List<Long>, mmsIds: List<Long>): List<MirrorSpamStateRow>

    open suspend fun rows(ids: List<Long>): List<MirrorSpamStateRow> = ids.distinct().chunked(400).flatMap { batch ->
        rowsBySource(batch.filter { it > 0 }, batch.filter { it < 0 && it != Long.MIN_VALUE }.map { -it })
    }

    @Query("SELECT " + SPAM_COLUMNS + " FROM mirror_message")
    abstract suspend fun allRows(): List<MirrorSpamStateRow>

    @Query("SELECT COUNT(*) FROM mirror_message")
    abstract fun observeChanges(): Flow<Int>

    @Update(entity = MirrorMessageEntity::class)
    protected abstract suspend fun updateState(update: MirrorSpamUpdate)

    private suspend fun persist(old: MirrorSpamStateRow, changed: MirrorSpamStateRow) {
        val resolved = changed.resolved()
        if (old != resolved) updateState(resolved.update())
    }

    @Transaction
    open suspend fun savePrediction(
        messageId: Long,
        score: Float,
        identity: String,
        revision: Long,
        generation: Long?,
        manual: Boolean,
        expectedMessageRevision: Long? = null,
        expectedLocalId: Long? = null,
    ): Boolean {
        require(score.isFinite() && score in 0f..1f)
        val old = rows(listOf(messageId)).firstOrNull() ?: return false
        if (expectedMessageRevision != null && old.messageRevision != expectedMessageRevision) return false
        if (expectedLocalId != null && old.localId != expectedLocalId) return false
        persist(old, old.copy(spamScore = score, spamModelIdentity = identity, spamModelRevision = revision,
            spamModelGeneration = generation, spamModelManual = manual))
        return true
    }

    @Transaction
    open suspend fun setKeyword(messageId: Long, keywordId: Long?, expectedVersions: Map<Long, Pair<Long, Long>>? = null) {
        rows(listOf(messageId)).filter { it.matches(expectedVersions) }
            .forEach { persist(it, it.copy(spamKeywordId = keywordId)) }
    }

    @Query("SELECT " + SPAM_COLUMNS + " FROM mirror_message WHERE spamKeywordId=:keywordId")
    protected abstract suspend fun keywordRows(keywordId: Long): List<MirrorSpamStateRow>

    @Transaction
    open suspend fun clearKeyword(keywordId: Long) {
        keywordRows(keywordId).forEach { persist(it, it.copy(spamKeywordId = null)) }
    }

    @Transaction
    open suspend fun replaceKeywords(matches: Map<Long, Long>, expectedVersions: Map<Long, Pair<Long, Long>>? = null) {
        allRows().filter { it.matches(expectedVersions) }
            .forEach { persist(it, it.copy(spamKeywordId = matches[it.messageId])) }
    }

    @Transaction
    open suspend fun allow(ids: Collection<Long>, source: Int = MessageSpamSource.WHITELIST,
        expectedVersions: Map<Long, Pair<Long, Long>>? = null) {
        require(source == MessageSpamSource.WHITELIST || source == MessageSpamSource.RELEASED)
        rows(ids.toList()).filter { it.matches(expectedVersions) }.forEach {
            val reason = if (it.spamAllowedSource == MessageSpamSource.RELEASED) MessageSpamSource.RELEASED else source
            persist(it, it.copy(spamAllowedSource = reason))
        }
    }

    @Transaction
    open suspend fun setCorrection(ids: Collection<Long>, spam: Boolean?, expectedVersions: Map<Long, Pair<Long, Long>>? = null) {
        rows(ids.toList()).filter { it.matches(expectedVersions) }
            .forEach { persist(it, it.copy(spamCorrection = spam)) }
    }

    @Transaction
    open suspend fun replaceCorrections(corrections: Map<Long, Boolean>, expectedVersions: Map<Long, Pair<Long, Long>>? = null) {
        allRows().filter { it.matches(expectedVersions) }
            .forEach { persist(it, it.copy(spamCorrection = corrections[it.messageId])) }
    }

    private fun MirrorSpamStateRow.matches(expected: Map<Long, Pair<Long, Long>>?): Boolean =
        expected == null || expected[messageId] == (localId to messageRevision)

    /** 一次性迁移只填充尚未判定的同一条消息，不能回退已经发生的新操作。 */
    @Transaction
    open suspend fun importDecisions(imported: List<MirrorSpamStateRow>) {
        val current = rows(imported.map { it.messageId }).associateBy { it.messageId }
        imported.forEach { next ->
            val old = current[next.messageId] ?: return@forEach
            if (old.localId == next.localId && old.messageRevision == next.messageRevision &&
                old.spamSource == MessageSpamSource.UNKNOWN && old.spamScore == null &&
                old.spamAllowedSource == null && old.spamKeywordId == null && old.spamCorrection == null) {
                persist(old, next)
            }
        }
    }

    open suspend fun getScore(messageId: Long): Float? = rows(listOf(messageId)).firstOrNull()?.effectiveScore

    open suspend fun getScores(ids: List<Long>): Map<Long, Float> = buildMap {
        rows(ids).forEach { row -> row.effectiveScore?.let { put(row.messageId, it) } }
    }

    open suspend fun spamMessageIds(ids: List<Long>, threshold: Float = MessageSpamSource.THRESHOLD): Set<Long> =
        rows(ids).filter { (it.effectiveScore ?: -1f) >= threshold }.mapTo(mutableSetOf()) { it.messageId }

    open suspend fun identifiedMessageIds(ids: List<Long>): Set<Long> =
        rows(ids).filter { it.isSpam != null }.mapTo(mutableSetOf()) { it.messageId }

    @Query("SELECT CASE WHEN transport='MMS' THEN -sourceId ELSE sourceId END FROM mirror_message WHERE isSpam=1")
    protected abstract suspend fun markedSpamIds(): List<Long>

    open suspend fun allSpamMessageIds(threshold: Float = MessageSpamSource.THRESHOLD): Set<Long> =
        if (threshold == MessageSpamSource.THRESHOLD) markedSpamIds().toSet()
        else allRows().filter { (it.effectiveScore ?: -1f) >= threshold }.mapTo(mutableSetOf()) { it.messageId }

    @Query("""
        SELECT threadId FROM mirror_message
        WHERE threadId > 0 AND
            CASE WHEN spamSource=1 THEN spamScore WHEN isSpam=1 THEN 1.0 WHEN isSpam=0 THEN 0.0 ELSE -1.0 END >= :threshold
        GROUP BY threadId ORDER BY MAX(timestamp) DESC, MAX(localId) DESC LIMIT :limit OFFSET :offset
    """)
    protected abstract suspend fun scoredThreadIds(threshold: Float, limit: Int, offset: Int): List<Long>

    @Query("""
        SELECT threadId FROM mirror_message WHERE isSpam=1 AND threadId > 0
        GROUP BY threadId ORDER BY MAX(timestamp) DESC, MAX(localId) DESC LIMIT :limit OFFSET :offset
    """)
    protected abstract suspend fun markedSpamThreadIds(limit: Int, offset: Int): List<Long>

    open suspend fun spamThreadIds(threshold: Float, limit: Int, offset: Int): List<Long> =
        if (threshold == MessageSpamSource.THRESHOLD) markedSpamThreadIds(limit, offset)
        else scoredThreadIds(threshold, limit, offset)
}
