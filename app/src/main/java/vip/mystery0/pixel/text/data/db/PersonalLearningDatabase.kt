package vip.mystery0.pixel.text.data.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "learning_sample", indices = [Index("digest")])
data class LearningSampleEntity(@PrimaryKey val id: String, val digest: String, val text: String,
    val label: String, val updatedAt: Long, val revision: Long)
@Entity(tableName = "learning_link", primaryKeys = ["sourceType", "sourceId"], indices = [Index("sampleId")])
data class LearningLinkEntity(val sourceType: String, val sourceId: Long, val fingerprint: String, val sampleId: String)
@Entity(tableName = "learning_state")
data class LearningStateEntity(@PrimaryKey val id: Int = 1, val revision: Long = 0,
    val status: String = "IDLE", val error: String? = null)
@Entity(tableName = "learning_parameters")
data class LearningParametersEntity(@PrimaryKey val identity: String, val revision: Long, val payload: ByteArray)
data class LearningSampleRow(@Embedded val sample: LearningSampleEntity, val linkedMessageCount: Int)
@Dao
interface PersonalLearningDao {
    @Query("SELECT s.*, (SELECT COUNT(*) FROM learning_link l WHERE l.sampleId=s.id) AS linkedMessageCount FROM learning_sample s ORDER BY updatedAt DESC")
    fun observeSamples(): Flow<List<LearningSampleRow>>
    @Query("SELECT * FROM learning_parameters WHERE identity=:identity") suspend fun parameters(identity: String): LearningParametersEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putParameters(parameters: LearningParametersEntity)
    @Query("DELETE FROM learning_parameters") suspend fun clearParameters()
    @Query("SELECT * FROM learning_state WHERE id=1") fun observeState(): Flow<LearningStateEntity?>
    @Query("SELECT * FROM learning_state WHERE id=1") suspend fun state(): LearningStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putState(state: LearningStateEntity)
    @Query("SELECT * FROM learning_sample ORDER BY id") suspend fun samples(): List<LearningSampleEntity>
    @Query("SELECT * FROM learning_sample WHERE digest=:digest") suspend fun byDigest(digest: String): List<LearningSampleEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSample(sample: LearningSampleEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLink(link: LearningLinkEntity)
    @Query("SELECT s.label FROM learning_link l JOIN learning_sample s ON s.id=l.sampleId WHERE l.sourceType=:type AND l.sourceId=:id AND l.fingerprint=:fingerprint")
    suspend fun correction(type: String, id: Long, fingerprint: String): String?
    @Query("DELETE FROM learning_link WHERE sourceType=:type AND sourceId=:id AND fingerprint=:fingerprint")
    suspend fun unlink(type: String, id: Long, fingerprint: String)
    @Query("SELECT * FROM learning_link") suspend fun links(): List<LearningLinkEntity>
    @Query("DELETE FROM learning_link WHERE sourceType=:type AND sourceId IN (:ids)") suspend fun unlinkIds(type: String, ids: List<Long>)
    @Query("DELETE FROM learning_link WHERE sampleId=:id") suspend fun deleteLinks(id: String)
    @Query("DELETE FROM learning_sample WHERE id=:id") suspend fun deleteSample(id: String)
    @Query("DELETE FROM learning_sample") suspend fun clearSamples()
    @Query("DELETE FROM learning_link") suspend fun clearLinks()
}
@Database(entities = [LearningSampleEntity::class, LearningLinkEntity::class, LearningStateEntity::class, LearningParametersEntity::class], version = 1, exportSchema = false)
abstract class PersonalLearningDatabase : RoomDatabase() {
    abstract fun dao(): PersonalLearningDao
    companion object {
        fun create(context: Context): PersonalLearningDatabase = Room.databaseBuilder(
            context.applicationContext, PersonalLearningDatabase::class.java, "personal_learning.db"
        ).build()
    }
}
