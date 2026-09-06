package vip.mystery0.pixel.text.data.db.mirror

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MirrorSyncDao {
    @Query("SELECT * FROM mirror_sync_state") abstract fun observeStates(): Flow<List<MirrorSyncStateEntity>>
    @Query("SELECT * FROM mirror_sync_state WHERE collection=:collection") abstract suspend fun state(collection: String): MirrorSyncStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putState(entity: MirrorSyncStateEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun markDirty(entity: MirrorDirtyEntity)
    @Query("SELECT * FROM mirror_sync_dirty LIMIT 200") abstract suspend fun dirtyBatch(): List<MirrorDirtyEntity>
    @Query("SELECT * FROM mirror_sync_dirty WHERE `key`=:key") abstract suspend fun dirty(key: String): MirrorDirtyEntity?
    @Query("DELETE FROM mirror_sync_dirty WHERE `key`=:key AND token=:token") abstract suspend fun acknowledge(key: String, token: String)
    @Query("SELECT COUNT(*) FROM mirror_sync_dirty") abstract suspend fun dirtyCount(): Int
    @Query("SELECT COUNT(*) FROM mirror_sync_dirty") abstract fun observeDirtyCount(): Flow<Int>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putThreads(rows: List<MirrorThreadSourceEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putCanonical(rows: List<MirrorCanonicalAddressEntity>)
    @Query("DELETE FROM mirror_thread_source") abstract suspend fun clearThreads()
    @Query("DELETE FROM mirror_canonical_address") abstract suspend fun clearCanonical()
    @Transaction open suspend fun replaceThreads(rows: List<MirrorThreadSourceEntity>, state: MirrorSyncStateEntity) {
        clearThreads(); putThreads(rows); putState(state)
    }
    @Transaction open suspend fun replaceCanonical(rows: List<MirrorCanonicalAddressEntity>, state: MirrorSyncStateEntity) {
        clearCanonical(); putCanonical(rows); putState(state)
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putDownloadRequest(request: MmsDownloadRequestEntity)
    @Query("SELECT * FROM mms_download_request WHERE token=:token") abstract suspend fun downloadRequest(token: String): MmsDownloadRequestEntity?
    @Query("SELECT * FROM mms_download_request WHERE sourceMmsId=:sourceId ORDER BY updatedAt DESC LIMIT 1") abstract suspend fun latestDownloadRequest(sourceId: Long): MmsDownloadRequestEntity?
    @Query("SELECT * FROM mms_download_request WHERE stage NOT IN ('COMPLETE','FAILED')") abstract suspend fun unfinishedDownloads(): List<MmsDownloadRequestEntity>
}
