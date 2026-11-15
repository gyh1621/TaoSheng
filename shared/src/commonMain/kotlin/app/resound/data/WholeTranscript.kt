package app.resound.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** 同一个原始录音的第二次识别，不是另一次录音，也不含推测的说话人或逐字时间。 */
@Entity(tableName = "whole_transcripts", foreignKeys = [ForeignKey(entity = SegmentEntity::class,
    parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.CASCADE)])
data class WholeTranscript(
    @PrimaryKey val segmentId: Long,
    val generation: Long = 1,
    val text: String = "",
    val status: String = "Pending",
    val error: String? = null,
    val attempts: Int = 0,
    val updatedAt: Long = 0,
)

@Dao
abstract class WholeTranscriptDao {
    @Query("SELECT segmentId FROM transcript_lines WHERE id=:id")
    abstract suspend fun segmentIdForLine(id: Long): Long?
    @Query("SELECT * FROM whole_transcripts WHERE segmentId = :id")
    abstract fun observe(id: Long): Flow<WholeTranscript?>
    @Query("SELECT * FROM whole_transcripts")
    abstract fun observeAll(): Flow<List<WholeTranscript>>
    @Query("SELECT * FROM whole_transcripts WHERE segmentId = :id")
    abstract suspend fun get(id: Long): WholeTranscript?
    @Query("SELECT * FROM whole_transcripts WHERE segmentId IN (:ids) ORDER BY segmentId")
    abstract suspend fun getMany(ids: List<Long>): List<WholeTranscript>
    @Query("SELECT * FROM whole_transcripts WHERE status = 'Pending' OR (status = 'Failed' AND attempts < 3) ORDER BY segmentId")
    abstract suspend fun pending(): List<WholeTranscript>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun put(row: WholeTranscript)
    @Query("SELECT EXISTS(SELECT 1 FROM segments WHERE id = :id)")
    protected abstract suspend fun exists(id: Long): Boolean
    @Transaction
    open suspend fun enqueue(id: Long, at: Long, replace: Boolean = false) {
        if (!exists(id)) return
        val old = get(id)
        if (old != null && !replace) return
        put(WholeTranscript(id, (old?.generation ?: 0) + 1, old?.text.orEmpty(), updatedAt = at))
    }
    @Query("UPDATE whole_transcripts SET status='Running', error=NULL, attempts=attempts+1 WHERE segmentId=:id AND generation=:generation AND status IN ('Pending','Failed')")
    abstract suspend fun claim(id: Long, generation: Long): Int
    @Query("UPDATE whole_transcripts SET status='Done', text=:text, error=:notice, updatedAt=:at WHERE segmentId=:id AND generation=:generation AND status='Running'")
    abstract suspend fun complete(id: Long, generation: Long, text: String, at: Long, notice: String? = null): Int
    @Query("UPDATE whole_transcripts SET status='Failed', error=:error, updatedAt=:at WHERE segmentId=:id AND generation=:generation AND status='Running'")
    abstract suspend fun fail(id: Long, generation: Long, error: String, at: Long)
    @Query("UPDATE whole_transcripts SET status='Pending', attempts=MAX(attempts-1,0), error='任务中断，等待重试' WHERE status='Running'")
    abstract suspend fun recover()
    @Query("SELECT segmentId FROM whole_transcripts WHERE status IN ('Pending','Running','Failed')")
    abstract suspend fun pendingIds(): List<Long>
    @Query("UPDATE whole_transcripts SET status='Deleted', text='', error=NULL, generation=generation+1 WHERE segmentId=:id")
    abstract suspend fun invalidate(id: Long)
    @Query("UPDATE whole_transcripts SET status='Skipped', error='用户选择本次仅使用切分版整理', generation=generation+1 WHERE segmentId=:id AND status='Failed'")
    abstract suspend fun useSegmentedOnly(id: Long): Int
    @Query("DELETE FROM whole_transcripts WHERE segmentId IN (:ids)")
    abstract suspend fun delete(ids: List<Long>)
}
