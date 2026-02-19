package zaujaani.roadsensebasic.data.repository

import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.RoadSenseDatabase
import zaujaani.roadsensebasic.data.local.entity.RoadEvent
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurveyRepository @Inject constructor(
    db: RoadSenseDatabase
) {
    private val sessionDao = db.sessionDao()
    private val eventDao = db.eventDao()

    // Session
    suspend fun insertSession(session: SurveySession): Long =
        sessionDao.insertSession(session)

    suspend fun updateSession(session: SurveySession) =
        sessionDao.updateSession(session)

    suspend fun getSessionById(sessionId: Long): SurveySession? =
        sessionDao.getSessionById(sessionId)

    fun getSessionsWithCount(): Flow<List<SessionWithCount>> =
        sessionDao.getSessionsWithCount()

    suspend fun deleteSessionById(sessionId: Long) =
        sessionDao.deleteSessionById(sessionId)

    // Events
    suspend fun insertEvent(event: RoadEvent) =
        eventDao.insertEvent(event)

    suspend fun getEventsForSession(sessionId: Long): List<RoadEvent> =
        eventDao.getEventsForSession(sessionId)
}
