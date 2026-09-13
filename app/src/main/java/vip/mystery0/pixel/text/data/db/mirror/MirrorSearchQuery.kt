package vip.mystery0.pixel.text.data.db.mirror

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import vip.mystery0.pixel.text.domain.model.search.MessageSearchFilter
import vip.mystery0.pixel.text.domain.model.search.MessageSearchRequest
import vip.mystery0.pixel.text.domain.model.search.SearchPhoneNumbers

object MirrorSearchQuery {
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun appendFilterClauses(
        filter: MessageSearchFilter,
        cutoff: Long?,
        clauses: MutableList<String>,
        args: MutableList<Any>,
    ) {
        // 1. Effective Transports
        val transports = filter.effectiveTransports
        if (transports.size == 1) {
            clauses.add("m.transport = ?")
            args.add(transports.first().name)
        }

        // 2. SIM subIds
        val simSubIds = filter.simSubIds
        if (simSubIds.isNotEmpty()) {
            clauses.add("m.subscriptionId IN (${simSubIds.joinToString(",") { "?" }})")
            args.addAll(simSubIds)
        }

        // 3. Unread: 未知 read 非已读语义
        if (filter.unreadOnly) {
            clauses.add("(m.read IS NULL OR m.read != 1)")
        }

        // 4. Date Cutoff (timestamp < cutoff)
        if (cutoff != null) {
            clauses.add("m.timestamp IS NOT NULL AND m.timestamp < ?")
            args.add(cutoff)
        }

        // 5. Phone Number Aliases
        val phoneNumber = filter.phoneNumber
        val aliases = if (!phoneNumber.isNullOrBlank()) SearchPhoneNumbers.queryAliases(phoneNumber) else emptySet()
        if (aliases.isNotEmpty()) {
            val smsPhoneClauses = aliases.joinToString(" OR ") { "s.normalizedAddress LIKE '%' || ? || '%'" }
            val mmsFromClauses = aliases.joinToString(" OR ") { "a.normalizedAddress LIKE '%' || ? || '%'" }
            val mmsRecipientClauses = aliases.joinToString(" OR ") { "a.normalizedAddress LIKE '%' || ? || '%'" }

            val phoneSql = """
                (
                  (m.transport = 'SMS' AND ($smsPhoneClauses))
                  OR
                  (m.transport = 'MMS' AND (
                    (
                      EXISTS (
                        SELECT 1 FROM mirror_mms_address af
                        WHERE af.localId = m.localId AND af.type = 137
                          AND af.address != 'insert-address-token'
                          AND af.normalizedAddress IS NOT NULL AND af.normalizedAddress != ''
                      )
                      AND EXISTS (
                        SELECT 1 FROM mirror_mms_address a
                        WHERE a.localId = m.localId AND a.type = 137
                          AND a.address != 'insert-address-token'
                          AND ($mmsFromClauses)
                      )
                    )
                    OR
                    (
                      NOT EXISTS (
                        SELECT 1 FROM mirror_mms_address af
                        WHERE af.localId = m.localId AND af.type = 137
                          AND af.address != 'insert-address-token'
                          AND af.normalizedAddress IS NOT NULL AND af.normalizedAddress != ''
                      )
                      AND EXISTS (
                        SELECT 1 FROM mirror_mms_address a
                        WHERE a.localId = m.localId AND a.type IN (151, 130, 129)
                          AND a.address != 'insert-address-token'
                          AND ($mmsRecipientClauses)
                      )
                    )
                  ))
                )
            """.trimIndent()
            clauses.add(phoneSql)
            args.addAll(aliases)
            args.addAll(aliases)
            args.addAll(aliases)
        }
    }

    fun buildSearchQuery(request: MessageSearchRequest): SupportSQLiteQuery {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        appendFilterClauses(request.filter, request.beforeTimestampExclusive, clauses, args)

        // 6. Keyword Search
        val selectArgs = mutableListOf<Any>()
        val hitBodySql: String
        val hitSubjectSql: String

        if (request.hasEffectiveQuery) {
            val escaped = escapeLike(request.query)
            val pattern = "%$escaped%"

            hitBodySql = """
                CASE
                  WHEN tx.localId IS NOT NULL AND tx.version = 3 AND tx.sourceRevision = m.revision AND tx.searchBody LIKE ? ESCAPE '\'
                    THEN tx.searchBody
                  WHEN (tx.localId IS NULL OR tx.version != 3 OR tx.sourceRevision != m.revision)
                    THEN (
                      SELECT p.text FROM mirror_mms_part p
                      WHERE p.localId = m.localId
                        AND p.mimeType = 'text/plain'
                        AND p.text IS NOT NULL
                        AND p.text LIKE ? ESCAPE '\'
                      ORDER BY p.sequence, p.sourceId
                      LIMIT 1
                    )
                  ELSE NULL
                END
            """.trimIndent()
            selectArgs.add(pattern)
            selectArgs.add(pattern)

            hitSubjectSql = """
                CASE
                  WHEN COALESCE(mm.decodedSubject, mm.subject) LIKE ? ESCAPE '\'
                    THEN COALESCE(mm.decodedSubject, mm.subject)
                  ELSE NULL
                END
            """.trimIndent()
            selectArgs.add(pattern)

            val keywordSql = """
                (
                  (m.transport = 'SMS' AND s.body LIKE ? ESCAPE '\')
                  OR
                  (m.transport = 'MMS' AND (
                    COALESCE(mm.decodedSubject, mm.subject) LIKE ? ESCAPE '\'
                    OR
                    (
                      tx.localId IS NOT NULL AND tx.version = 3 AND tx.sourceRevision = m.revision
                      AND tx.searchBody LIKE ? ESCAPE '\'
                    )
                    OR
                    (
                      (tx.localId IS NULL OR tx.version != 3 OR tx.sourceRevision != m.revision)
                      AND EXISTS (
                        SELECT 1 FROM mirror_mms_part p
                        WHERE p.localId = m.localId
                          AND p.mimeType = 'text/plain'
                          AND p.text IS NOT NULL
                          AND p.text LIKE ? ESCAPE '\'
                      )
                    )
                  ))
                )
            """.trimIndent()
            clauses.add(keywordSql)
            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
        } else {
            hitBodySql = "NULL"
            hitSubjectSql = "NULL"
        }

        val whereSection = if (clauses.isNotEmpty()) "WHERE " + clauses.joinToString(" AND\n  ") else ""

        val sql = """
            SELECT
              m.localId AS localId,
              m.transport AS transport,
              m.sourceId AS sourceId,
              m.threadId AS threadId,
              m.timestamp AS timestamp,
              m.subscriptionId AS subscriptionId,
              m.boxType AS boxType,
              m.read AS read,
              COALESCE(
                s.address,
                (SELECT a.address FROM mirror_mms_address a WHERE a.localId = m.localId AND a.address != 'insert-address-token' AND a.type = (CASE WHEN m.boxType = 1 THEN 137 ELSE 151 END) ORDER BY a.ordinal LIMIT 1),
                (SELECT a.address FROM mirror_mms_address a WHERE a.localId = m.localId AND a.address != 'insert-address-token' ORDER BY a.ordinal LIMIT 1)
              ) AS address,
              s.body AS smsBody,
              mm.subject AS mmsSubject,
              mm.decodedSubject AS mmsDecodedSubject,
              tx.summary AS mmsSummary,
              mm.pduType AS pduType,
              COALESCE(
                CASE
                  WHEN tx.localId IS NOT NULL AND tx.version = 3 AND tx.sourceRevision = m.revision THEN tx.searchBody
                  ELSE NULL
                END,
                (
                  SELECT p.text FROM mirror_mms_part p
                  WHERE p.localId = m.localId
                    AND p.mimeType = 'text/plain'
                    AND p.text IS NOT NULL
                    AND p.text != ''
                  ORDER BY p.sequence, p.sourceId
                  LIMIT 1
                )
              ) AS mmsEffectiveBody,
              $hitBodySql AS mmsHitBody,
              $hitSubjectSql AS mmsHitSubject
            FROM mirror_message m
            LEFT JOIN mirror_sms s ON s.localId = m.localId
            LEFT JOIN mirror_mms mm ON mm.localId = m.localId
            LEFT JOIN mms_text_index tx ON tx.localId = m.localId
            $whereSection
            ORDER BY m.timestamp DESC, m.localId DESC
        """.trimIndent()

        val allArgs = selectArgs + args
        return SimpleSQLiteQuery(sql, allArgs.toTypedArray())
    }

    fun buildUnreadyCountQuery(request: MessageSearchRequest): SupportSQLiteQuery {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        clauses.add("m.transport = 'MMS'")
        clauses.add("(mm.pduType IS NULL OR mm.pduType != 130)")
        clauses.add("(tx.localId IS NULL OR tx.version != 3 OR tx.sourceRevision != m.revision OR tx.searchReady = 0)")

        appendFilterClauses(request.filter, request.beforeTimestampExclusive, clauses, args)

        val sql = """
            SELECT COUNT(*)
            FROM mirror_message m
            LEFT JOIN mirror_sms s ON s.localId = m.localId
            LEFT JOIN mirror_mms mm ON mm.localId = m.localId
            LEFT JOIN mms_text_index tx ON tx.localId = m.localId
            WHERE ${clauses.joinToString(" AND\n  ")}
        """.trimIndent()

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
