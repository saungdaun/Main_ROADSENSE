package zaujaani.roadsensebasic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.entity.DistressItem

@Dao
interface DistressItemDao {

    @Insert
    suspend fun insertDistress(item: DistressItem): Long

    @Update
    suspend fun updateDistress(item: DistressItem)

    @Query("SELECT * FROM distress_items WHERE segmentId = :segmentId ORDER BY createdAt ASC")
    fun getDistressForSegment(segmentId: Long): Flow<List<DistressItem>>

    @Query("SELECT * FROM distress_items WHERE segmentId = :segmentId ORDER BY createdAt ASC")
    suspend fun getDistressForSegmentOnce(segmentId: Long): List<DistressItem>

    @Query("SELECT * FROM distress_items WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getDistressForSession(sessionId: Long): List<DistressItem>

    @Query("DELETE FROM distress_items WHERE segmentId = :segmentId")
    suspend fun deleteDistressForSegment(segmentId: Long)

    @Query("DELETE FROM distress_items WHERE sessionId = :sessionId")
    suspend fun deleteDistressForSession(sessionId: Long)
}