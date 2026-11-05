package vip.mystery0.pixel.text.data.db.mirror

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MirrorDao {
    @Query("SELECT MAX(localId) FROM mirror_message")
    abstract suspend fun maximumLocalId(): Long?

    @Query("SELECT COUNT(*) FROM mirror_message WHERE transport=:transport AND localId <= :upperLocalId")
    abstract suspend fun initializationCount(transport: String, upperLocalId: Long): Long

    @Transaction
    @Query("SELECT * FROM mirror_message WHERE transport=:transport AND localId > :afterLocalId AND localId <= :upperLocalId ORDER BY localId LIMIT :limit")
    abstract suspend fun initializationBatch(
        transport: String,
        afterLocalId: Long,
        upperLocalId: Long,
        limit: Int
    ): List<MirrorMessageRecord>
    @Query("SELECT * FROM mms_text_index WHERE localId=:localId")
    abstract suspend fun getMmsText(localId: Long): MmsTextIndexEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMmsText(entity: MmsTextIndexEntity)

    @Transaction @Query("SELECT * FROM mirror_message WHERE transport=:transport AND sourceId=:sourceId")
    abstract suspend fun get(transport: String, sourceId: Long): MirrorMessageRecord?

    @Transaction @Query("SELECT * FROM mirror_message WHERE transport=:transport AND sourceId=:sourceId")
    abstract fun observeMessage(transport: String, sourceId: Long): Flow<MirrorMessageRecord?>

    @Transaction @Query("SELECT * FROM mirror_message WHERE localId=:localId")
    abstract suspend fun getLocal(localId: Long): MirrorMessageRecord?

    @Transaction @Query("SELECT * FROM mirror_message WHERE threadId=:threadId ORDER BY timestamp DESC, localId DESC LIMIT :limit OFFSET :offset")
    abstract fun observeThread(threadId: Long, limit: Int, offset: Int): Flow<List<MirrorMessageRecord>>

    /** 过滤和分页都在 SQL 内完成，只加载当前页需要的正文与彩信关系。 */
    @Transaction
    @Query("""
        SELECT * FROM mirror_message WHERE threadId=:threadId AND
            (:filter=0 OR (:filter=1 AND (isSpam=0 OR isSpam IS NULL)) OR (:filter=2 AND isSpam=1))
        ORDER BY timestamp DESC, localId DESC LIMIT :limit OFFSET :offset
    """)
    abstract suspend fun threadPage(threadId: Long, filter: Int, limit: Int, offset: Int): List<MirrorMessageRecord>

    // 仅订阅关联表失效，无需为每次变化解码整段历史。
    @Query("SELECT COUNT(*) FROM mirror_message m LEFT JOIN mirror_attachment a ON a.localId=m.localId LEFT JOIN mms_text_index tx ON tx.localId=m.localId WHERE m.threadId=:threadId")
    abstract fun observeThreadChanges(threadId: Long): Flow<Long>

    @Transaction @Query("SELECT * FROM mirror_message ORDER BY timestamp DESC, localId DESC")
    abstract fun observeAll(): Flow<List<MirrorMessageRecord>>

    @Transaction @Query("SELECT * FROM mirror_message WHERE transport='MMS' ORDER BY timestamp DESC, localId DESC")
    abstract fun observeMms(): Flow<List<MirrorMessageRecord>>

    @Transaction @Query("SELECT * FROM mirror_message WHERE transport='MMS' AND threadId IN (:threadIds) ORDER BY timestamp DESC, localId DESC")
    abstract suspend fun conversationMms(threadIds: List<Long>): List<MirrorMessageRecord>

    @Query("""
        SELECT m.localId, m.sourceId, m.threadId, m.timestamp, m.originalDate, m.revision,
            m.subscriptionId, m.boxType, m.read, s.body, s.address, s.dateSent
        FROM mirror_message m LEFT JOIN mirror_sms s ON s.localId=m.localId
        WHERE m.transport='SMS' AND m.threadId IN (:threadIds)
        ORDER BY m.timestamp DESC, m.localId DESC
    """)
    abstract suspend fun conversationSms(threadIds: List<Long>): List<MirrorConversationSmsRow>

    @Query("""
        SELECT m.localId, m.sourceId, m.threadId, m.timestamp, m.originalDate, m.revision,
            m.subscriptionId, m.boxType, m.read, s.body, s.address, s.dateSent
        FROM mirror_message m LEFT JOIN mirror_sms s ON s.localId=m.localId
        WHERE m.transport='SMS' AND m.sourceId IN (:sourceIds)
        ORDER BY m.timestamp DESC, m.localId DESC
    """)
    abstract suspend fun conversationSmsByIds(sourceIds: List<Long>): List<MirrorConversationSmsRow>

    @Transaction @Query("SELECT * FROM mirror_message WHERE transport='MMS' AND sourceId IN (:sourceIds) ORDER BY timestamp DESC, localId DESC")
    abstract suspend fun conversationMmsByIds(sourceIds: List<Long>): List<MirrorMessageRecord>

    @Transaction @Query("SELECT * FROM mirror_message WHERE transport='MMS' AND localId IN (:localIds)")
    abstract suspend fun conversationMmsByLocalIds(localIds: List<Long>): List<MirrorMessageRecord>

    @RawQuery
    abstract suspend fun filteredConversations(query: SupportSQLiteQuery): List<MirrorFilteredConversationRow>

    @RawQuery(observedEntities = [MirrorMessageEntity::class, MirrorSmsEntity::class,
        MirrorMmsEntity::class, MirrorAddressEntity::class, MirrorPartEntity::class,
        MirrorAttachmentEntity::class, MmsTextIndexEntity::class])
    abstract fun observeConversationChanges(query: SupportSQLiteQuery): Flow<Int>

    @Query("""
        SELECT m.threadId AS threadId,
          COALESCE(s.address, (SELECT a.address FROM mirror_mms_address a WHERE a.localId=m.localId
            AND a.address != 'insert-address-token' AND a.type=(CASE WHEN m.boxType=1 THEN 137 ELSE 151 END) ORDER BY a.ordinal LIMIT 1),
            (SELECT a.address FROM mirror_mms_address a WHERE a.localId=m.localId AND a.address != 'insert-address-token' ORDER BY a.ordinal LIMIT 1)) AS address,
          COALESCE(s.body, tx.summary, mm.decodedSubject, mm.subject, '正在准备彩信内容') AS snippet,
          m.timestamp AS timestamp,
          (SELECT COUNT(*) FROM mirror_message u WHERE u.threadId=m.threadId AND u.read=0 AND u.boxType=1) AS unreadCount,
          m.transport='MMS' AS latestIsMms,
          EXISTS(SELECT 1 FROM mirror_message x WHERE x.threadId=m.threadId AND x.transport='MMS') AS containsMms
        FROM mirror_message m LEFT JOIN mirror_sms s ON s.localId=m.localId LEFT JOIN mirror_mms mm ON mm.localId=m.localId LEFT JOIN mms_text_index tx ON tx.localId=m.localId
        WHERE m.threadId > 0 AND m.localId=(SELECT n.localId FROM mirror_message n WHERE n.threadId=m.threadId ORDER BY n.timestamp DESC, n.localId DESC LIMIT 1)
        ORDER BY m.timestamp DESC, m.localId DESC
    """)
    abstract fun observeConversations(): Flow<List<MirrorConversationRow>>

    @Query("SELECT * FROM mirror_message WHERE transport=:transport AND generation != :generation AND localId > :afterLocalId ORDER BY localId LIMIT 200")
    abstract suspend fun missingCandidates(transport: String, generation: Long, afterLocalId: Long): List<MirrorMessageEntity>

    @Insert abstract suspend fun insertMessage(entity: MirrorMessageEntity): Long
    @Query("DELETE FROM mms_text_index WHERE localId=:localId")
    abstract suspend fun deleteMmsText(localId: Long)
    @Query("SELECT revision FROM mirror_message WHERE localId=:localId")
    abstract suspend fun messageRevision(localId: Long): Long?
    @Update abstract suspend fun updateMessageRow(entity: MirrorMessageEntity)
    @Transaction open suspend fun updateMessage(entity: MirrorMessageEntity) {
        val previous = messageRevision(entity.localId)
        if (previous != entity.revision) deleteMmsText(entity.localId)
        updateMessageRow(entity)
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putSms(entity: MirrorSmsEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putMms(entity: MirrorMmsEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putAddresses(entities: List<MirrorAddressEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putParts(entities: List<MirrorPartEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putAttachmentRow(entity: MirrorAttachmentEntity)
    @Transaction open suspend fun putAttachment(entity: MirrorAttachmentEntity) {
        val previous = attachment(entity.localId, entity.partId)
        if (previous?.state != entity.state || previous.relativePath != entity.relativePath ||
            previous.sha256 != entity.sha256 || previous.byteCount != entity.byteCount) deleteMmsText(entity.localId)
        putAttachmentRow(entity)
    }
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun enqueueCleanup(entity: MirrorFileCleanupEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putSyncState(entity: MirrorSyncStateEntity)
    @Query("SELECT * FROM mirror_sync_state WHERE collection='spam_content_change'")
    abstract suspend fun spamContentChange(): MirrorSyncStateEntity?
    @Query("UPDATE mirror_sync_state SET complete=1 WHERE collection='spam_content_change' AND generation=:generation")
    abstract suspend fun completeSpamContentChange(generation: Long): Int
    @Query("DELETE FROM mirror_mms_address WHERE localId=:localId") abstract suspend fun deleteAddresses(localId: Long)
    @Query("DELETE FROM mirror_mms_part WHERE localId=:localId") abstract suspend fun deleteParts(localId: Long)
    @Query("DELETE FROM mirror_message WHERE localId=:localId") abstract suspend fun deleteMessage(localId: Long)

    /** 只在子结构完整时替换；Provider 与文件读写始终在事务外进行。 */
    @Transaction
    open suspend fun replaceStructure(structure: MirrorStructure): Long {
        val old = get(structure.message.transport, structure.message.sourceId)
        val id = old?.message?.localId ?: insertMessage(structure.message)
        var root = structure.message.copy(localId = id)
        val sms = structure.sms?.copy(localId = id)
        val mms = structure.mms?.copy(localId = id)
        val addresses = structure.addresses.map { it.copy(localId = id) }
        val parts = structure.parts.map { it.copy(localId = id) }
        val sameChildren = old != null && old.addresses.sortedBy { it.ordinal } == addresses &&
            old.parts.sortedBy { it.sourceId } == parts.sortedBy { it.sourceId }
        // Provider 的已读、收件箱和会话变更不是正文变化，不能抹掉已经保存的判定。
        // 身份或正文实际改变时清除旧判定，避免 Provider ID 复用继承人工放行。
        val sameIdentity = old != null && old.message.originalDate == root.originalDate &&
            if (root.transport == "SMS") {
                old.sms?.body == sms?.body && old.sms?.address == sms?.address && old.sms?.dateSent == sms?.dateSent
            } else {
                old.mms?.subject == mms?.subject && old.mms?.decodedSubject == mms?.decodedSubject &&
                    old.mms?.transactionId == mms?.transactionId && old.mms?.contentLocation == mms?.contentLocation &&
                    (!structure.childrenComplete || sameChildren)
            }
        if (sameIdentity) root = root.copySpamStateFrom(requireNotNull(old).message)
        val sameRoot = old != null && old.sms == sms && old.mms == mms &&
            old.message.copy(generation = root.generation, revision = root.revision) == root
        val revision = if (old == null) 1 else if (sameRoot && (!structure.childrenComplete || sameChildren))
            old.message.revision else old.message.revision + 1
        updateMessage(root.copy(revision = revision))
        sms?.let { putSms(it) }
        mms?.let { putMms(it) }
        if (structure.childrenComplete) {
            val retained = structure.attachments.map { next ->
                val previous = old?.attachments?.find { it.partId == next.partId }
                val previousPart = old?.parts?.find { it.sourceId == next.partId }
                val currentPart = parts.find { it.sourceId == next.partId }
                if (previous != null && previousPart == currentPart) previous.copy(revision = revision)
                else next.copy(localId = id, revision = revision)
            }
            val retainedPaths = retained.mapNotNull { it.relativePath }.toSet()
            old?.attachments?.mapNotNull { it.relativePath }?.filterNot { it in retainedPaths }?.forEach {
                enqueueCleanup(MirrorFileCleanupEntity(it))
            }
            deleteAddresses(id)
            deleteParts(id)
            putAddresses(addresses)
            putParts(parts)
            // 重放同一结构时旧附件已被级联删除，不能把重放误判为内容变化。
            // 实际结构变化已在上面的 revision 更新中清除派生索引。
            retained.forEach { putAttachmentRow(it) }
        } else {
            // 头发生变化时旧附件暂不可视为新结构的完整副本，但不丢失已有文件。
            old?.attachments?.forEach { putAttachment(it.copy(revision = revision)) }
        }
        if (!sameIdentity) {
            // 与消息写入同事务记录补识别代次；已读和会话变化不会安排历史重扫。
            val generation = (spamContentChange()?.generation ?: 0L) + 1L
            putSyncState(MirrorSyncStateEntity("spam_content_change", generation = generation, complete = false))
        }
        return id
    }

    @Transaction
    open suspend fun commitBatch(structures: List<MirrorStructure>, checkpoint: MirrorSyncStateEntity) {
        structures.forEach { replaceStructure(it) }
        putSyncState(checkpoint)
    }

    @Transaction
    open suspend fun deleteConfirmed(localId: Long, revision: Long): Boolean {
        val record = getLocal(localId) ?: return false
        if (record.message.revision != revision) return false
        record.attachments.mapNotNull { it.relativePath }.forEach { enqueueCleanup(MirrorFileCleanupEntity(it)) }
        deleteMessage(localId)
        return true
    }

    @Query("SELECT a.* FROM mirror_attachment a JOIN mirror_message m ON m.localId=a.localId WHERE m.structureComplete=1 AND a.state IN ('SOURCE_PRESENT','COPY_FAILED','SOURCE_UNREADABLE') AND a.retryAfter <= :now ORDER BY a.retryAfter, a.localId LIMIT 100")
    abstract suspend fun pendingAttachments(now: Long): List<MirrorAttachmentEntity>

    @Query("SELECT COUNT(*) FROM mirror_attachment WHERE state IN ('SOURCE_PRESENT','COPYING','COPY_FAILED','SOURCE_UNREADABLE')")
    abstract suspend fun unfinishedAttachmentCount(): Int

    @Query("SELECT * FROM mirror_message WHERE transport='MMS' AND localId > :afterId ORDER BY localId LIMIT 200")
    abstract suspend fun mmsVerificationBatch(afterId: Long): List<MirrorMessageEntity>

    @Transaction
    open suspend fun invalidateAttachmentContent(sourceId: Long) {
        val record = get("MMS", sourceId) ?: return
        if (record.attachments.none { it.state == "COPYING" || it.state == "READY" }) return
        // 提升代次，阻止锁外读取的旧字节在变更事件之后重新绑定。
        val revision = record.message.revision + 1
        updateMessage(record.message.copy(revision = revision))
        record.attachments.forEach { attachment ->
            val changed = attachment.state == "COPYING" || attachment.state == "READY"
            putAttachment(if (changed) attachment.copy(revision = revision, state = "SOURCE_PRESENT", verifiedAt = null)
                else attachment.copy(revision = revision))
        }
    }

    @Query("SELECT * FROM mirror_attachment WHERE localId=:localId AND partId=:partId")
    abstract suspend fun attachment(localId: Long, partId: Long): MirrorAttachmentEntity?

    @Query("SELECT * FROM mirror_attachment") abstract suspend fun allAttachments(): List<MirrorAttachmentEntity>
    @Query("SELECT COUNT(*) FROM mirror_attachment WHERE relativePath=:path") abstract suspend fun referenceCount(path: String): Int
    @Query("SELECT * FROM mirror_file_cleanup WHERE relativePath > :afterPath ORDER BY relativePath LIMIT 200")
    abstract suspend fun cleanupTasks(afterPath: String = ""): List<MirrorFileCleanupEntity>
    @Query("SELECT COUNT(*) FROM mirror_file_cleanup") abstract suspend fun cleanupCount(): Int
    @Query("DELETE FROM mirror_file_cleanup WHERE relativePath=:path") abstract suspend fun removeCleanup(path: String)
    @Query("UPDATE mirror_file_cleanup SET attempts=attempts+1 WHERE relativePath=:path") abstract suspend fun failedCleanup(path: String)

    @Transaction
    open suspend fun updateAttachmentIfCurrent(entity: MirrorAttachmentEntity): Boolean {
        val message = getLocal(entity.localId) ?: return false
        val previous = attachment(entity.localId, entity.partId) ?: return false
        if (message.message.revision != entity.revision || previous.revision != entity.revision) return false
        if (previous.relativePath != entity.relativePath) previous.relativePath?.let { enqueueCleanup(MirrorFileCleanupEntity(it)) }
        putAttachment(entity)
        return true
    }

    @Query("SELECT COUNT(*) FROM mirror_message WHERE structureComplete=0") abstract fun observeIncompleteCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM mirror_mms WHERE pduType=130") abstract fun observePendingDownloads(): Flow<Int>
    @Query("SELECT COUNT(*) FROM mirror_attachment WHERE state IN ('COPY_FAILED','SOURCE_UNREADABLE')") abstract fun observeAttachmentFailures(): Flow<Int>
    @Query("SELECT COUNT(*) FROM mirror_attachment WHERE state IN ('SOURCE_PRESENT','COPYING')") abstract fun observePendingAttachments(): Flow<Int>
}
