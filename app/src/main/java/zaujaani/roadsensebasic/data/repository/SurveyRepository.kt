package zaujaani.roadsensebasic.data.repository

import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.RoadSenseDatabase
import zaujaani.roadsensebasic.data.local.entity.RoadEvent
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurveyRepository @Inject constructor(
    private val db: RoadSenseDatabase
) {
    private val sessionDao = db.sessionDao()
    private val eventDao = db.eventDao()

    // Session
    suspend fun insertSession(session: SurveySession): Long = sessionDao.insertSession(session)
    suspend fun updateSession(session: SurveySession) = sessionDao.updateSession(session)
    suspend fun getSessionById(sessionId: Long): SurveySession? = sessionDao.getSessionById(sessionId)
    fun getAllSessions(): Flow<List<SurveySession>> = sessionDao.getAllSessions()
    fun getSessionsWithCount(): Flow<List<SessionWithCount>> = sessionDao.getSessionsWithCount()
    suspend fun deleteSessionById(sessionId: Long) = sessionDao.deleteSessionById(sessionId)
    suspend fun updateSessionEnd(
        sessionId: Long,
        endTime: Long,
        totalDistance: Double,
        avgConfidence: Int,
        endLat: Double,
        endLng: Double
    ) = sessionDao.updateSessionEnd(sessionId, endTime, totalDistance, avgConfidence, endLat, endLng)

    // Events
    suspend fun insertEvent(event: RoadEvent) = eventDao.insertEvent(event)
    suspend fun getEventsForSession(sessionId: Long): List<RoadEvent> = eventDao.getEventsForSession(sessionId)
    suspend fun deleteEventsForSession(sessionId: Long) = eventDao.deleteEventsForSession(sessionId)

    // Compatibility (can be removed after cleaning up old references)
    suspend fun getSegmentsForSessionOnce(sessionId: Long): List<Nothing> = emptyList()
}