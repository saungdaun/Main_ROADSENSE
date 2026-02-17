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
    suspend fun insert(session: SurveySession): Long

    @Update
    suspend fun update(session: SurveySession)

    @Query("SELECT * FROM survey_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): SurveySession?

    @Query("SELECT * FROM survey_sessions ORDER BY startTime DESC")
    fun getAllSessionsFlow(): Flow<List<SurveySession>>

    @Query("SELECT * FROM survey_sessions ORDER BY startTime DESC")
    suspend fun getAllSessionsOnce(): List<SurveySession>

    @Query("DELETE FROM survey_sessions WHERE id = :sessionId")
    suspend fun delete(sessionId: Long)

    @Query("""
        SELECT s.*, COUNT(r.id) AS segmentCount 
        FROM survey_sessions s 
        LEFT JOIN road_segments r ON s.id = r.sessionId 
        GROUP BY s.id 
        ORDER BY s.startTime DESC
    """)
    fun getSessionsWithCount(): Flow<List<SessionWithCount>>
}