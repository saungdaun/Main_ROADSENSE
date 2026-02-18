package zaujaani.roadsensebasic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import zaujaani.roadsensebasic.data.local.entity.RoadEvent

@Dao
interface EventDao {
    @Insert
    suspend fun insertEvent(event: RoadEvent)

    @Query("SELECT * FROM road_events WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getEventsForSession(sessionId: Long): List<RoadEvent>

    @Query("DELETE FROM road_events WHERE sessionId = :sessionId")
    suspend fun deleteEventsForSession(sessionId: Long)
}