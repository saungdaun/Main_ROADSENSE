package zaujaani.roadsensebasic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.repository.SessionWithCount

@Dao
interface SessionDao {

    @Insert
    suspend fun insertSession(session: SurveySession): Long

    @Update
    suspend fun updateSession(session: SurveySession)

    @Query("SELECT * FROM survey_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<SurveySession>>

    @Query("SELECT * FROM survey_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): SurveySession?

    @Query("""
        SELECT *, 
               (SELECT COUNT(*) FROM road_events WHERE sessionId = survey_sessions.id) as eventCount 
        FROM survey_sessions 
        ORDER BY startTime DESC
    """)
    fun getSessionsWithCount(): Flow<List<SessionWithCount>>

    @Query("DELETE FROM survey_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    @Query("UPDATE survey_sessions SET endTime = :endTime, totalDistance = :totalDistance, avgConfidence = :avgConfidence, endLat = :endLat, endLng = :endLng WHERE id = :sessionId")
    suspend fun updateSessionEnd(sessionId: Long, endTime: Long, totalDistance: Double, avgConfidence: Int, endLat: Double, endLng: Double)
}