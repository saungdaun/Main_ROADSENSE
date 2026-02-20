package zaujaani.roadsensebasic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.entity.SegmentSdi

@Dao
interface SegmentSdiDao {

    @Insert
    suspend fun insertSegment(segment: SegmentSdi): Long

    @Update
    suspend fun updateSegment(segment: SegmentSdi)

    @Query("SELECT * FROM segment_sdi WHERE sessionId = :sessionId ORDER BY segmentIndex ASC")
    fun getSegmentsForSession(sessionId: Long): Flow<List<SegmentSdi>>

    @Query("SELECT * FROM segment_sdi WHERE sessionId = :sessionId ORDER BY segmentIndex ASC")
    suspend fun getSegmentsForSessionOnce(sessionId: Long): List<SegmentSdi>

    @Query("SELECT * FROM segment_sdi WHERE id = :segmentId")
    suspend fun getSegmentById(segmentId: Long): SegmentSdi?

    @Query("UPDATE segment_sdi SET sdiScore = :sdiScore, distressCount = :distressCount WHERE id = :segmentId")
    suspend fun updateSegmentScore(segmentId: Long, sdiScore: Int, distressCount: Int)

    @Query("DELETE FROM segment_sdi WHERE sessionId = :sessionId")
    suspend fun deleteSegmentsForSession(sessionId: Long)
}