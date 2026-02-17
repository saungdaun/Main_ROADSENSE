package zaujaani.roadsensebasic.data.repository

import androidx.room.ColumnInfo
import androidx.room.Embedded
import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.RoadSenseDatabase
import zaujaani.roadsensebasic.data.local.entity.RoadSegment
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurveyRepository @Inject constructor(
    private val db: RoadSenseDatabase
) {
    private val sessionDao = db.sessionDao()
    private val segmentDao = db.segmentDao()

    // ========== SESSION ==========
    suspend fun createSession(session: SurveySession): Long {
        return sessionDao.insert(session)
    }

    suspend fun updateSession(session: SurveySession) {
        sessionDao.update(session)
    }

    suspend fun getSessionById(sessionId: Long): SurveySession? {
        return sessionDao.getSessionById(sessionId)
    }

    fun getAllSessionsFlow(): Flow<List<SurveySession>> {
        return sessionDao.getAllSessionsFlow()
    }

    suspend fun getAllSessionsOnce(): List<SurveySession> {
        return sessionDao.getAllSessionsOnce()
    }

    suspend fun deleteSessionById(sessionId: Long) {
        // Hapus semua segmen terkait terlebih dahulu
        segmentDao.deleteBySession(sessionId)
        // Hapus sesi
        sessionDao.delete(sessionId)
    }

    // ========== SEGMENT ==========
    suspend fun insertSegment(segment: RoadSegment) {
        segmentDao.insert(segment)
    }

    suspend fun insertAllSegments(segments: List<RoadSegment>) {
        segmentDao.insertAll(segments)
    }

    fun getSegmentsForSessionFlow(sessionId: Long): Flow<List<RoadSegment>> {
        return segmentDao.getSegmentsForSessionFlow(sessionId)
    }

    suspend fun getSegmentsForSessionOnce(sessionId: Long): List<RoadSegment> {
        return segmentDao.getSegmentsForSessionOnce(sessionId)
    }

    suspend fun deleteSegmentsForSession(sessionId: Long) {
        segmentDao.deleteBySession(sessionId)
    }

    suspend fun getSegmentCountForSession(sessionId: Long): Int {
        return segmentDao.getCountForSession(sessionId)
    }

    // ========== GABUNGAN (SESSION + COUNT SEGMENT) ==========
    fun getSessionsWithCount(): Flow<List<SessionWithCount>> {
        return sessionDao.getSessionsWithCount()
    }
}

/**
 * Data class untuk menyimpan hasil gabungan sesi dan jumlah segmen.
 */
data class SessionWithCount(
    @Embedded
    val session: SurveySession,
    @ColumnInfo(name = "segmentCount")
    val segmentCount: Int
)