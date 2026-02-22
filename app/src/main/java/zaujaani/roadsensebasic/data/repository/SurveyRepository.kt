package zaujaani.roadsensebasic.data.repository

import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import zaujaani.roadsensebasic.data.local.RoadSenseDatabase
import zaujaani.roadsensebasic.data.local.entity.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurveyRepository @Inject constructor(
    db: RoadSenseDatabase
) {
    private val sessionDao       = db.sessionDao()
    private val segmentDao       = db.segmentDao()         // RoadSegment (GENERAL mode)
    private val eventDao         = db.eventDao()
    private val segmentSdiDao    = db.segmentSdiDao()
    private val distressItemDao  = db.distressItemDao()
    private val segmentPciDao    = db.segmentPciDao()      // ← BARU
    private val pciDistressDao   = db.pciDistressItemDao() // ← BARU

    // ── Session ───────────────────────────────────────────────────────────

    suspend fun insertSession(session: SurveySession): Long = sessionDao.insertSession(session)
    suspend fun updateSession(session: SurveySession) = sessionDao.updateSession(session)
    suspend fun getSessionById(sessionId: Long): SurveySession? = sessionDao.getSessionById(sessionId)
    fun getSessionsWithCount(): Flow<List<SessionWithCount>> = sessionDao.getSessionsWithCount()
    suspend fun deleteSessionById(sessionId: Long) = sessionDao.deleteSessionById(sessionId)

    // ── RoadSegment (GENERAL mode) ─────────────────────────────────────────

    suspend fun insertSegment(segment: RoadSegment): Long = segmentDao.insert(segment)
    suspend fun insertSegments(segments: List<RoadSegment>) = segmentDao.insertAll(segments)
    fun getSegmentsForSessionFlow(sessionId: Long): Flow<List<RoadSegment>> =
        segmentDao.getSegmentsForSessionFlow(sessionId)
    suspend fun getSegmentsForSessionOnce(sessionId: Long): List<RoadSegment> =
        segmentDao.getSegmentsForSessionOnce(sessionId)
    suspend fun deleteSegmentsBySession(sessionId: Long) = segmentDao.deleteBySession(sessionId)

    // ── Events ────────────────────────────────────────────────────────────

    suspend fun insertEvent(event: RoadEvent) = eventDao.insertEvent(event)
    suspend fun getEventsForSession(sessionId: Long): List<RoadEvent> =
        eventDao.getEventsForSession(sessionId)

    // ── SDI ───────────────────────────────────────────────────────────────

    suspend fun insertSegmentSdi(segment: SegmentSdi): Long = segmentSdiDao.insertSegment(segment)
    suspend fun updateSegmentSdi(segment: SegmentSdi) = segmentSdiDao.updateSegment(segment)
    suspend fun updateSegmentSdiScore(segmentId: Long, sdiScore: Int, distressCount: Int) =
        segmentSdiDao.updateSegmentScore(segmentId, sdiScore, distressCount)
    fun getSegmentSdiForSessionFlow(sessionId: Long): Flow<List<SegmentSdi>> =
        segmentSdiDao.getSegmentsForSession(sessionId)
    suspend fun getSegmentSdiForSessionOnce(sessionId: Long): List<SegmentSdi> =
        segmentSdiDao.getSegmentsForSessionOnce(sessionId)
    suspend fun getSegmentSdiById(segmentId: Long): SegmentSdi? =
        segmentSdiDao.getSegmentById(segmentId)

    // ── Distress Items (SDI) ──────────────────────────────────────────────

    suspend fun insertDistressItem(item: DistressItem): Long = distressItemDao.insertDistress(item)
    suspend fun updateDistressItem(item: DistressItem) = distressItemDao.updateDistress(item)
    fun getDistressForSegmentFlow(segmentId: Long): Flow<List<DistressItem>> =
        distressItemDao.getDistressForSegment(segmentId)
    suspend fun getDistressForSegmentOnce(segmentId: Long): List<DistressItem> =
        distressItemDao.getDistressForSegmentOnce(segmentId)
    suspend fun getDistressForSession(sessionId: Long): List<DistressItem> =
        distressItemDao.getDistressForSession(sessionId)
    suspend fun deleteDistressForSegment(segmentId: Long) =
        distressItemDao.deleteDistressForSegment(segmentId)

    // ── PCI Segments ──────────────────────────────────────────────────────

    suspend fun insertSegmentPci(segment: SegmentPci): Long =
        segmentPciDao.insertSegment(segment)

    suspend fun updateSegmentPci(segment: SegmentPci) =
        segmentPciDao.updateSegment(segment)

    suspend fun updateSegmentPciScore(
        segmentId: Long,
        pciScore: Int,
        pciRating: String,
        cdv: Double,
        distressCount: Int,
        dominantType: String,
        dvList: List<Double>
    ) = segmentPciDao.updatePciScore(
        segmentId     = segmentId,
        pciScore      = pciScore,
        pciRating     = pciRating,
        cdv           = cdv,
        distressCount = distressCount,
        dominantType  = dominantType,
        dvJson        = JSONArray(dvList).toString()
    )

    fun getSegmentPciForSessionFlow(sessionId: Long): Flow<List<SegmentPci>> =
        segmentPciDao.getSegmentsForSession(sessionId)

    suspend fun getSegmentPciForSessionOnce(sessionId: Long): List<SegmentPci> =
        segmentPciDao.getSegmentsForSessionOnce(sessionId)

    suspend fun getSegmentPciById(segmentId: Long): SegmentPci? =
        segmentPciDao.getSegmentById(segmentId)

    suspend fun getAveragePci(sessionId: Long): Int =
        segmentPciDao.getAveragePci(sessionId)?.toInt() ?: -1

    // ── PCI Distress Items ────────────────────────────────────────────────

    suspend fun insertPciDistressItem(item: PCIDistressItem): Long =
        pciDistressDao.insert(item)

    suspend fun updatePciDistressItem(item: PCIDistressItem) =
        pciDistressDao.update(item)

    fun getPciDistressForSegmentFlow(segmentId: Long): Flow<List<PCIDistressItem>> =
        pciDistressDao.getItemsForSegment(segmentId)

    suspend fun getPciDistressForSegmentOnce(segmentId: Long): List<PCIDistressItem> =
        pciDistressDao.getItemsForSegmentOnce(segmentId)

    suspend fun getPciDistressForSession(sessionId: Long): List<PCIDistressItem> =
        pciDistressDao.getItemsForSession(sessionId)

    suspend fun deletePciDistressForSegment(segmentId: Long) =
        pciDistressDao.deleteBySegment(segmentId)
}