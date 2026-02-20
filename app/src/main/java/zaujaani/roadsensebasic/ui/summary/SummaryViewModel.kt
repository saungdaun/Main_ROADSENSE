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
import zaujaani.roadsensebasic.data.local.entity.RoadEvent
import zaujaani.roadsensebasic.data.local.entity.SegmentSdi
import zaujaani.roadsensebasic.data.local.entity.SurveyMode
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.repository.SessionWithCount
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.data.repository.TelemetryRepository
import zaujaani.roadsensebasic.util.FileExporter
import zaujaani.roadsensebasic.util.PDFExporter
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

data class SessionDetailUi(
    val session: SurveySession,
    val events: List<RoadEvent>,
    val totalDistance: Double,
    val durationMinutes: Int,
    val avgConfidence: Int,
    val photoCount: Int,
    val audioCount: Int,
    val conditionDistribution: Map<String, Double>,
    val surfaceDistribution: Map<String, Double>,
    // SDI fields
    val mode: SurveyMode,
    val segmentsSdi: List<SegmentSdi> = emptyList(),
    val distressItems: List<DistressItem> = emptyList(),
    val averageSdi: Int = 0
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val surveyRepository: SurveyRepository,
    private val telemetryRepository: TelemetryRepository,
    @param:ApplicationContext
    private val context: Context
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
                val events = surveyRepository.getEventsForSession(sessionId)

                val totalDistance = session.totalDistance
                val durationMinutes = ((session.endTime ?: session.startTime) - session.startTime) / 60000

                val avgConfidence = if (telemetries.isNotEmpty()) {
                    telemetries.map { (100 - (it.gpsAccuracy * 2)).coerceIn(0f, 100f).toInt() }.average().roundToInt()
                } else 0

                val photoCount = events.count { it.eventType == EventType.PHOTO }
                val audioCount = events.count { it.eventType == EventType.VOICE }

                val conditionMap = mutableMapOf<String, Double>()
                val surfaceMap = mutableMapOf<String, Double>()

                if (telemetries.size >= 2) {
                    var lastDist = telemetries.first().cumulativeDistance
                    var lastCondition = telemetries.first().condition.name
                    var lastSurface = telemetries.first().surface.name

                    for (i in 1 until telemetries.size) {
                        val t = telemetries[i]
                        val delta = t.cumulativeDistance - lastDist
                        if (delta > 0) {
                            conditionMap[lastCondition] = conditionMap.getOrDefault(lastCondition, 0.0) + delta
                            surfaceMap[lastSurface] = surfaceMap.getOrDefault(lastSurface, 0.0) + delta
                        }
                        lastDist = t.cumulativeDistance
                        lastCondition = t.condition.name
                        lastSurface = t.surface.name
                    }
                }

                // Ambil data SDI
                val mode = session.mode
                val segmentsSdi = if (mode == SurveyMode.SDI) {
                    surveyRepository.getSegmentsForSessionOnce(sessionId)
                } else emptyList()

                val distressItems = if (mode == SurveyMode.SDI) {
                    surveyRepository.getDistressForSession(sessionId)
                } else emptyList()

                val averageSdi = if (segmentsSdi.isNotEmpty()) {
                    segmentsSdi.map { it.sdiScore }.average().toInt()
                } else 0

                val detail = SessionDetailUi(
                    session = session,
                    events = events,
                    totalDistance = totalDistance,
                    durationMinutes = durationMinutes.toInt(),
                    avgConfidence = avgConfidence,
                    photoCount = photoCount,
                    audioCount = audioCount,
                    conditionDistribution = conditionMap,
                    surfaceDistribution = surfaceMap,
                    mode = mode,
                    segmentsSdi = segmentsSdi,
                    distressItems = distressItems,
                    averageSdi = averageSdi
                )
                _sessionDetail.value = detail
                Timber.d("Loaded detail for session $sessionId with ${telemetries.size} telemetry points, avgConfidence=$avgConfidence, mode=$mode")
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

    fun exportSessionToGpx(sessionId: Long, callback: (File?) -> Unit) {
        viewModelScope.launch {
            try {
                val session = surveyRepository.getSessionById(sessionId) ?: return@launch callback(null)
                val telemetries = telemetryRepository.getTelemetryForSessionOnce(sessionId)
                if (telemetries.isEmpty()) return@launch callback(null)
                val events = surveyRepository.getEventsForSession(sessionId)
                val file = FileExporter.exportToGpx(
                    context = context,
                    sessionName = "RoadSense_${session.startTime}",
                    telemetries = telemetries,
                    events = events
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
                val session = surveyRepository.getSessionById(sessionId) ?: return@launch callback(null)
                val telemetries = telemetryRepository.getTelemetryForSessionOnce(sessionId)
                val events = surveyRepository.getEventsForSession(sessionId)
                val file = FileExporter.exportToCsv(
                    context = context,
                    sessionName = "RoadSense_${session.startTime}",
                    session = session,
                    telemetries = telemetries,
                    events = events
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
                val session = surveyRepository.getSessionById(sessionId) ?: return@launch callback(null)
                val telemetries = telemetryRepository.getTelemetryForSessionOnce(sessionId)
                val events = surveyRepository.getEventsForSession(sessionId)
                val mode = session.mode

                val segmentsSdi = if (mode == SurveyMode.SDI) {
                    surveyRepository.getSegmentsForSessionOnce(sessionId)
                } else emptyList()

                val distressItems = if (mode == SurveyMode.SDI) {
                    surveyRepository.getDistressForSession(sessionId)
                } else emptyList()

                // Hitung avgConfidence
                val avgConfidence = if (telemetries.isNotEmpty()) {
                    telemetries.map { (100 - (it.gpsAccuracy * 2)).coerceIn(0f, 100f).toInt() }.average().roundToInt()
                } else 0

                // Hitung distribusi kondisi dan permukaan
                val conditionMap = mutableMapOf<String, Double>()
                val surfaceMap = mutableMapOf<String, Double>()

                if (telemetries.size >= 2) {
                    var lastDist = telemetries.first().cumulativeDistance
                    var lastCondition = telemetries.first().condition.name
                    var lastSurface = telemetries.first().surface.name

                    for (i in 1 until telemetries.size) {
                        val t = telemetries[i]
                        val delta = t.cumulativeDistance - lastDist
                        if (delta > 0) {
                            conditionMap[lastCondition] = conditionMap.getOrDefault(lastCondition, 0.0) + delta
                            surfaceMap[lastSurface] = surfaceMap.getOrDefault(lastSurface, 0.0) + delta
                        }
                        lastDist = t.cumulativeDistance
                        lastCondition = t.condition.name
                        lastSurface = t.surface.name
                    }
                }

                val file = PDFExporter.exportToPdf(
                    context = context,
                    session = session,
                    telemetries = telemetries,
                    events = events,
                    segmentsSdi = segmentsSdi,
                    distressItems = distressItems,
                    conditionDistribution = conditionMap,
                    surfaceDistribution = surfaceMap
                )
                callback(file)
            } catch (e: Exception) {
                Timber.e(e, "PDF export error")
                callback(null)
            }
        }
    }
}