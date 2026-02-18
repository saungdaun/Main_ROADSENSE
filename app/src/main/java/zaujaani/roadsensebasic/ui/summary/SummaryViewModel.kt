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
import zaujaani.roadsensebasic.data.local.entity.EventType
import zaujaani.roadsensebasic.data.local.entity.RoadEvent
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.repository.SessionWithCount
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.data.repository.TelemetryRepository
import zaujaani.roadsensebasic.util.FileExporter
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
    val surfaceDistribution: Map<String, Double>
)

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
                val session = surveyRepository.getSessionById(sessionId)
                if (session == null) {
                    Timber.w("Session $sessionId not found")
                    _sessionDetail.value = null
                    return@launch
                }

                val telemetries = telemetryRepository.getTelemetryForSessionOnce(sessionId)
                val events = surveyRepository.getEventsForSession(sessionId)

                val totalDistance = session.totalDistance
                val durationMinutes = ((session.endTime ?: session.startTime) - session.startTime) / 60000
                val avgConfidence = 0 // Bisa dihitung nanti jika diperlukan

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

                val detail = SessionDetailUi(
                    session = session,
                    events = events,
                    totalDistance = totalDistance,
                    durationMinutes = durationMinutes.toInt(),
                    avgConfidence = avgConfidence,
                    photoCount = photoCount,
                    audioCount = audioCount,
                    conditionDistribution = conditionMap,
                    surfaceDistribution = surfaceMap
                )
                _sessionDetail.value = detail
                Timber.d("Loaded detail for session $sessionId with ${telemetries.size} telemetry points")
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
}