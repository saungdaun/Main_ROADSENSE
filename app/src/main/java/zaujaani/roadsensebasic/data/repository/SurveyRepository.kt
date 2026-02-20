package zaujaani.roadsensebasic.data.repository

import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.RoadSenseDatabase
import zaujaani.roadsensebasic.data.local.entity.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurveyRepository @Inject constructor(
    db: RoadSenseDatabase
) {
    private val sessionDao = db.sessionDao()
    private val eventDao = db.eventDao()
    private val segmentSdiDao = db.segmentSdiDao()
    private val distressItemDao = db.distressItemDao()

    // Session
    suspend fun insertSession(session: SurveySession): Long = sessionDao.insertSession(session)
    suspend fun updateSession(session: SurveySession) = sessionDao.updateSession(session)
    suspend fun getSessionById(sessionId: Long): SurveySession? = sessionDao.getSessionById(sessionId)
    fun getSessionsWithCount(): Flow<List<SessionWithCount>> = sessionDao.getSessionsWithCount()
    suspend fun deleteSessionById(sessionId: Long) = sessionDao.deleteSessionById(sessionId)

    // Events
    suspend fun insertEvent(event: RoadEvent) = eventDao.insertEvent(event)
    suspend fun getEventsForSession(sessionId: Long): List<RoadEvent> = eventDao.getEventsForSession(sessionId)

    // Segment SDI
    suspend fun insertSegmentSdi(segment: SegmentSdi): Long = segmentSdiDao.insertSegment(segment)
    suspend fun updateSegmentSdi(segment: SegmentSdi) = segmentSdiDao.updateSegment(segment)
    suspend fun updateSegmentSdiScore(segmentId: Long, sdiScore: Int, distressCount: Int) =
        segmentSdiDao.updateSegmentScore(segmentId, sdiScore, distressCount)
    fun getSegmentsForSession(sessionId: Long): Flow<List<SegmentSdi>> = segmentSdiDao.getSegmentsForSession(sessionId)
    suspend fun getSegmentsForSessionOnce(sessionId: Long): List<SegmentSdi> = segmentSdiDao.getSegmentsForSessionOnce(sessionId)
    suspend fun getSegmentById(segmentId: Long): SegmentSdi? = segmentSdiDao.getSegmentById(segmentId)

    // Distress Items
    suspend fun insertDistressItem(item: DistressItem): Long = distressItemDao.insertDistress(item)
    suspend fun updateDistressItem(item: DistressItem) = distressItemDao.updateDistress(item)
    fun getDistressForSegment(segmentId: Long): Flow<List<DistressItem>> = distressItemDao.getDistressForSegment(segmentId)
    suspend fun getDistressForSegmentOnce(segmentId: Long): List<DistressItem> = distressItemDao.getDistressForSegmentOnce(segmentId)
    suspend fun getDistressForSession(sessionId: Long): List<DistressItem> = distressItemDao.getDistressForSession(sessionId)
    suspend fun deleteDistressForSegment(segmentId: Long) = distressItemDao.deleteDistressForSegment(segmentId)
}