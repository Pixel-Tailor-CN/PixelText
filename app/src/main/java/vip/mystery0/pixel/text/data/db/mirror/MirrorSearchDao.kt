package vip.mystery0.pixel.text.data.db.mirror

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class MirrorSearchRow(
    val localId: Long,
    val transport: String,
    val sourceId: Long,
    val threadId: Long?,
    val timestamp: Long?,
    val subscriptionId: Int?,
    val boxType: Int?,
    val read: Int?,
    val address: String?,
    val smsBody: String?,
    val mmsSubject: String?,
    val mmsDecodedSubject: String?,
    val mmsSummary: String?,
    val pduType: Int?,
    val mmsEffectiveBody: String?,
    val mmsHitBody: String? = null,
    val mmsHitSubject: String? = null,
)

data class MirrorSearchResult(
    val rows: List<MirrorSearchRow>,
    val unreadyCount: Int,
)

@Dao
abstract class MirrorSearchDao {
    @RawQuery(
        observedEntities = [
            MirrorMessageEntity::class,
            MirrorSmsEntity::class,
            MirrorMmsEntity::class,
            MirrorAddressEntity::class,
            MirrorPartEntity::class,
            MmsTextIndexEntity::class,
        ]
    )
    abstract fun observeTableChanges(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery
    abstract suspend fun queryRows(query: SupportSQLiteQuery): List<MirrorSearchRow>

    @RawQuery
    abstract suspend fun countUnready(query: SupportSQLiteQuery): Int

    @Transaction
    open suspend fun searchBatch(
        searchQuery: SupportSQLiteQuery,
        unreadyQuery: SupportSQLiteQuery,
    ): MirrorSearchResult {
        val rows = queryRows(searchQuery)
        val unready = countUnready(unreadyQuery)
        return MirrorSearchResult(rows = rows, unreadyCount = unready)
    }
}
