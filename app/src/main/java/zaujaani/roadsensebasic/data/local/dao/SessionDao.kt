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

    // ─────────────────────────────────────────────
    // SESSION CRUD
    // ─────────────────────────────────────────────

    @Insert
    suspend fun insertSession(session: SurveySession): Long

    @Update
    suspend fun updateSession(session: SurveySession)

    @Query("""
        SELECT * 
        FROM survey_sessions
        WHERE id = :sessionId
    """)
    suspend fun getSessionById(sessionId: Long): SurveySession?

    @Query("""
        DELETE FROM survey_sessions 
        WHERE id = :sessionId
    """)
    suspend fun deleteSessionById(sessionId: Long)

    // ─────────────────────────────────────────────
    // SESSION LIST SUMMARY (PRO VERSION ⭐)
    // ─────────────────────────────────────────────

    @Query("""
        SELECT 
            survey_sessions.*,

            -- Event summary
            (SELECT COUNT(*) 
             FROM road_events 
             WHERE road_events.sessionId = survey_sessions.id) 
             AS eventCount,

            -- Photo count
            (SELECT COUNT(*) 
             FROM road_events 
             WHERE road_events.sessionId = survey_sessions.id
             AND road_events.eventType = 'PHOTO')
             AS photoCount,

            -- Audio count
            (SELECT COUNT(*) 
             FROM road_events 
             WHERE road_events.sessionId = survey_sessions.id
             AND road_events.eventType = 'VOICE')
             AS audioCount

        FROM survey_sessions
        ORDER BY startTime DESC
    """)
    fun getSessionsWithCount(): Flow<List<SessionWithCount>>

    // ─────────────────────────────────────────────
    // SESSION LIST BASIC
    // ─────────────────────────────────────────────

    @Query("""
        SELECT * 
        FROM survey_sessions 
        ORDER BY startTime DESC
    """)
    fun getAllSessions(): Flow<List<SurveySession>>

    // ─────────────────────────────────────────────
    // SESSION END UPDATE
    // ─────────────────────────────────────────────

    @Query("""
        UPDATE survey_sessions 
        SET 
            endTime = :endTime,
            totalDistance = :totalDistance,
            avgConfidence = :avgConfidence,
            endLat = :endLat,
            endLng = :endLng,
            durationMinutes = :durationMinutes
        WHERE id = :sessionId
    """)
    suspend fun updateSessionEnd(
        sessionId: Long,
        endTime: Long,
        totalDistance: Double,
        avgConfidence: Int,
        endLat: Double,
        endLng: Double,
        durationMinutes: Long
    )
}