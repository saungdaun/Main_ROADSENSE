package zaujaani.roadsensebasic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.entity.RoadSegment

@Dao
interface SegmentDao {
    @Insert
    suspend fun insert(segment: RoadSegment): Long

    @Insert
    suspend fun insertAll(segments: List<RoadSegment>)

    @Query("SELECT * FROM road_segments WHERE sessionId = :sessionId ORDER BY startDistance ASC")
    fun getSegmentsForSessionFlow(sessionId: Long): Flow<List<RoadSegment>>

    @Query("SELECT * FROM road_segments WHERE sessionId = :sessionId ORDER BY startDistance ASC")
    suspend fun getSegmentsForSessionOnce(sessionId: Long): List<RoadSegment>

    @Query("DELETE FROM road_segments WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("SELECT COUNT(*) FROM road_segments WHERE sessionId = :sessionId")
    suspend fun getCountForSession(sessionId: Long): Int
}