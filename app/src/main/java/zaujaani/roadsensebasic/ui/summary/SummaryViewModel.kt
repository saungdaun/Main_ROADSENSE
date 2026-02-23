package zaujaani.roadsensebasic.ui.summary

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsensebasic.data.local.entity.DistressItem
import zaujaani.roadsensebasic.data.local.entity.EventType
import zaujaani.roadsensebasic.data.local.entity.PCIDistressItem
import zaujaani.roadsensebasic.data.local.entity.RoadEvent
import zaujaani.roadsensebasic.data.local.entity.SegmentPci
import zaujaani.roadsensebasic.data.local.entity.SegmentSdi
import zaujaani.roadsensebasic.data.local.entity.SurveyMode
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.repository.SessionWithCount
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.data.repository.TelemetryRepository
import zaujaani.roadsensebasic.domain.engine.PCIRating
import zaujaani.roadsensebasic.util.FileExporter
import zaujaani.roadsensebasic.util.PDFExporter
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * RoadSense PRO UI Aggregation Model
 *
 * Gunakan ini untuk:
 * - SummaryFragment
 * - Export Report
 * - Detail Dialog Rendering
 *
 * FIX FOTO:
 * - generalPhotos → GENERAL mode (List<RoadEvent>)
 * - sdiPhotos → SDI distress documentation (List<String>)
 * - pciPhotos → PCI distress documentation (List<String>)
 */


@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val surveyRepository: SurveyRepository,
    private val telemetryRepository: TelemetryRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _sessions = MutableLiveData<List<SessionWithCount>>()
    val sessions: LiveData<List<SessionWithCount>> = _sessions

    private val _sessionDetail = MutableLiveData<SessionDetailUi?>()
    val sessionDetail: LiveData<SessionDetailUi?> = _sessionDetail

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    init {
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                surveyRepository.getSessionsWithCount().collect { list ->
                    _sessions.value = list
                    Timber.d("Loaded ${list.size} sessions")
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Timber.e(e, "Gagal memuat sesi")
                _sessions.value = emptyList()
                _isLoading.value = false
            }
        }
    }

    fun loadSessionDetail(sessionId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val session = surveyRepository.getSessionById(sessionId) ?: run {
                    Timber.w("Session $sessionId not found")
                    _sessionDetail.value = null
                    return@launch
                }

                val telemetries = telemetryRepository.getTelemetryForSessionOnce(sessionId)
                val events      = surveyRepository.getEventsForSession(sessionId)

                val totalDistance   = session.totalDistance
                // 🔧 Perbaikan: gunakan current time jika session belum berakhir
                val durationMinutes = ((session.endTime ?: System.currentTimeMillis()) - session.startTime) / 60000

                val avgConfidence = if (telemetries.isNotEmpty()) {
                    telemetries.map { (100 - (it.gpsAccuracy * 2)).coerceIn(0f, 100f).toInt() }
                        .average().roundToInt()
                } else 0

                val photoCount = events.count { it.eventType == EventType.PHOTO }
                val audioCount = events.count { it.eventType == EventType.VOICE }

                // Hitung distribusi kondisi & permukaan dari telemetri
                val conditionMap = mutableMapOf<String, Double>()
                val surfaceMap   = mutableMapOf<String, Double>()
                if (telemetries.size >= 2) {
                    var lastDist      = telemetries.first().cumulativeDistance
                    var lastCondition = telemetries.first().condition.name
                    var lastSurface   = telemetries.first().surface.name
                    for (i in 1 until telemetries.size) {
                        val t     = telemetries[i]
                        val delta = t.cumulativeDistance - lastDist
                        if (delta > 0) {
                            conditionMap[lastCondition] = conditionMap.getOrDefault(lastCondition, 0.0) + delta
                            surfaceMap[lastSurface]     = surfaceMap.getOrDefault(lastSurface, 0.0) + delta
                        }
                        lastDist      = t.cumulativeDistance
                        lastCondition = t.condition.name
                        lastSurface   = t.surface.name
                    }
                }

                val mode = session.mode

                // ── SDI data ──────────────────────────────────────────────
                val segmentsSdi = if (mode == SurveyMode.SDI) {
                    surveyRepository.getSegmentSdiForSessionOnce(sessionId)
                } else emptyList()

                val distressItems = if (mode == SurveyMode.SDI) {
                    surveyRepository.getDistressForSession(sessionId)
                } else emptyList()

                val averageSdi = if (segmentsSdi.isNotEmpty()) {
                    segmentsSdi.map { it.sdiScore }.average().toInt()
                } else 0

                // ── PCI data ──────────────────────────────────────────────
                val segmentsPci = if (mode == SurveyMode.PCI) {
                    surveyRepository.getSegmentPciForSessionOnce(sessionId)
                } else emptyList()

                val pciDistressItems = if (mode == SurveyMode.PCI) {
                    surveyRepository.getPciDistressForSession(sessionId)
                } else emptyList()

                val averagePci = if (segmentsPci.isNotEmpty()) {
                    segmentsPci.filter { it.pciScore >= 0 }
                        .map { it.pciScore }
                        .let { scores -> if (scores.isNotEmpty()) scores.average().toInt() else -1 }
                } else session.avgPci

                val pciRating = if (averagePci >= 0) PCIRating.fromScore(averagePci) else null

                // ⭐ KUMPULKAN FOTO BERDASARKAN MODE + CEK KEBERADAAN FILE
                val generalPhotos = events
                    .filter { it.eventType == EventType.PHOTO && it.value.isNotBlank() }

                val sdiPhotos = distressItems
                    .mapNotNull { it.photoPath }
                    .filter { it.isNotBlank() && File(it).exists() }

                val pciPhotos = pciDistressItems
                    .mapNotNull { it.photoPath }
                    .filter { it.isNotBlank() && File(it).exists() }

                // ── allPhotos: list terpadu dengan metadata lengkap (Issue #3) ──
                // Dipakai di photo slider untuk caption GPS + timestamp + distress info.
                // Build sekali di sini, Fragment tinggal pakai.
                val allPhotos: List<PhotoItem> = buildList {
                    // SDI distress photos
                    addAll(distressItems
                        .filter { it.photoPath.isNotBlank() && File(it.photoPath).exists() }
                        .map { item ->
                            PhotoItem(
                                path         = item.photoPath,
                                label        = "${item.type.displayName} (${item.severity.name}) | SDI",
                                sta          = item.sta,
                                source       = PhotoItem.Source.DISTRESS,
                                latitude     = item.gpsLat,
                                longitude    = item.gpsLng,
                                timestamp    = item.createdAt,
                                distressType = item.type.displayName,
                                severity     = item.severity.name
                            )
                        }
                    )
                    // PCI distress photos
                    addAll(pciDistressItems
                        .filter { it.photoPath.isNotBlank() && File(it.photoPath).exists() }
                        .map { item ->
                            PhotoItem(
                                path         = item.photoPath,
                                label        = "${item.type.displayName} (${item.severity.name}) | PCI",
                                sta          = item.sta,
                                notes        = item.notes,
                                source       = PhotoItem.Source.DISTRESS,
                                latitude     = item.gpsLat,
                                longitude    = item.gpsLng,
                                timestamp    = item.createdAt,
                                distressType = item.type.displayName,
                                severity     = item.severity.name
                            )
                        }
                    )
                    // General / FAB kamera — semua mode
                    addAll(generalPhotos
                        .filter { File(it.value).exists() }
                        .map { event ->
                            val km  = event.distance.toInt() / 1000
                            val m   = event.distance.toInt() % 1000
                            val sta = "STA %d+%03d".format(km, m)
                            PhotoItem(
                                path      = event.value,
                                label     = "Foto Lapangan | Umum",
                                sta       = sta,
                                notes     = event.notes ?: "",
                                source    = PhotoItem.Source.GENERAL,
                                latitude  = event.latitude,
                                longitude = event.longitude,
                                timestamp = event.timestamp
                            )
                        }
                    )
                }

                val detail = SessionDetailUi(
                    session               = session,
                    mode                  = mode,
                    totalDistance         = totalDistance,
                    durationMinutes       = durationMinutes,
                    avgConfidence         = avgConfidence,
                    averageSdi            = averageSdi,
                    segmentsSdi           = segmentsSdi,
                    distressItems         = distressItems,
                    averagePci            = averagePci,
                    pciRating             = pciRating,
                    segmentsPci           = segmentsPci,
                    pciDistressItems      = pciDistressItems,
                    events                = events,
                    generalPhotos         = generalPhotos,
                    sdiPhotos             = sdiPhotos,
                    pciPhotos             = pciPhotos,
                    allPhotos             = allPhotos,
                    conditionDistribution = conditionMap,
                    surfaceDistribution   = surfaceMap,
                    photoCount            = photoCount,
                    audioCount            = audioCount,
                )
                _sessionDetail.value = detail
                Timber.d("Loaded detail: sessionId=$sessionId mode=$mode avgSdi=$averageSdi avgPci=$averagePci")
            } catch (e: Exception) {
                Timber.e(e, "Gagal memuat detail sesi $sessionId")
                _sessionDetail.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteSession(session: SurveySession) {
        viewModelScope.launch {
            try {
                telemetryRepository.deleteBySession(session.id)
                surveyRepository.deleteSessionById(session.id)
                Timber.d("Deleted session ${session.id}")
                loadSessions()
            } catch (e: Exception) {
                Timber.e(e, "Gagal menghapus sesi ${session.id}")
            }
        }
    }

    // ── Export ────────────────────────────────────────────────────────────

    fun exportSessionToGpx(sessionId: Long, callback: (File?) -> Unit) {
        viewModelScope.launch {
            try {
                val session     = surveyRepository.getSessionById(sessionId) ?: return@launch callback(null)
                val telemetries = telemetryRepository.getTelemetryForSessionOnce(sessionId)
                if (telemetries.isEmpty()) return@launch callback(null)
                val events = surveyRepository.getEventsForSession(sessionId)
                val file = FileExporter.exportToGpx(
                    context     = context,
                    sessionName = "RoadSense_${session.startTime}",
                    telemetries = telemetries,
                    events      = events
                )
                callback(file)
            } catch (e: Exception) {
                Timber.e(e, "GPX export error")
                callback(null)
            }
        }
    }

    fun exportSessionToCsv(sessionId: Long, callback: (File?) -> Unit) {
        viewModelScope.launch {
            try {
                val session     = surveyRepository.getSessionById(sessionId) ?: return@launch callback(null)
                val telemetries = telemetryRepository.getTelemetryForSessionOnce(sessionId)
                val events      = surveyRepository.getEventsForSession(sessionId)
                val file = FileExporter.exportToCsv(
                    context     = context,
                    sessionName = "RoadSense_${session.startTime}",
                    session     = session,
                    telemetries = telemetries,
                    events      = events
                )
                callback(file)
            } catch (e: Exception) {
                Timber.e(e, "CSV export error")
                callback(null)
            }
        }
    }

    fun exportSessionToPdf(sessionId: Long, callback: (File?) -> Unit) {
        viewModelScope.launch {
            try {
                val session     = surveyRepository.getSessionById(sessionId) ?: return@launch callback(null)
                val telemetries = telemetryRepository.getTelemetryForSessionOnce(sessionId)
                val events      = surveyRepository.getEventsForSession(sessionId)
                val mode        = session.mode

                val segmentsSdi = if (mode == SurveyMode.SDI) {
                    surveyRepository.getSegmentSdiForSessionOnce(sessionId)
                } else emptyList()

                val distressItems = if (mode == SurveyMode.SDI) {
                    surveyRepository.getDistressForSession(sessionId)
                } else emptyList()

                val segmentsPci = if (mode == SurveyMode.PCI) {
                    surveyRepository.getSegmentPciForSessionOnce(sessionId)
                } else emptyList()

                val pciDistressItems = if (mode == SurveyMode.PCI) {
                    surveyRepository.getPciDistressForSession(sessionId)
                } else emptyList()

                val avgConfidence = if (telemetries.isNotEmpty()) {
                    telemetries.map { (100 - (it.gpsAccuracy * 2)).coerceIn(0f, 100f).toInt() }
                        .average().roundToInt()
                } else 0

                val conditionMap = mutableMapOf<String, Double>()
                val surfaceMap   = mutableMapOf<String, Double>()
                if (telemetries.size >= 2) {
                    var lastDist      = telemetries.first().cumulativeDistance
                    var lastCondition = telemetries.first().condition.name
                    var lastSurface   = telemetries.first().surface.name
                    for (i in 1 until telemetries.size) {
                        val t     = telemetries[i]
                        val delta = t.cumulativeDistance - lastDist
                        if (delta > 0) {
                            conditionMap[lastCondition] = conditionMap.getOrDefault(lastCondition, 0.0) + delta
                            surfaceMap[lastSurface]     = surfaceMap.getOrDefault(lastSurface, 0.0) + delta
                        }
                        lastDist      = t.cumulativeDistance
                        lastCondition = t.condition.name
                        lastSurface   = t.surface.name
                    }
                }

                val file = PDFExporter.exportToPdf(
                    context               = context,
                    session               = session,
                    telemetries           = telemetries,
                    events                = events,
                    segmentsSdi           = segmentsSdi,
                    distressItems         = distressItems,
                    conditionDistribution = conditionMap,
                    surfaceDistribution   = surfaceMap,
                    segmentsPci           = segmentsPci,
                    pciDistressItems      = pciDistressItems
                )
                callback(file)
            } catch (e: Exception) {
                Timber.e(e, "PDF export error")
                callback(null)
            }
        }
    }
}