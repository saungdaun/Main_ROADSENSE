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
import zaujaani.roadsensebasic.data.local.entity.RoadSegment
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.repository.SessionWithCount
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.data.repository.TelemetryRepository
import zaujaani.roadsensebasic.util.FileExporter
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * UI Model untuk menampilkan detail lengkap sesi.
 */
data class SessionDetailUi(
    val session: SurveySession,
    val segments: List<RoadSegment>,
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

    // Daftar sesi ringkas
    private val _sessions = MutableLiveData<List<SessionWithCount>>()
    val sessions: LiveData<List<SessionWithCount>> = _sessions

    // Detail sesi (untuk dialog)
    private val _sessionDetail = MutableLiveData<SessionDetailUi?>()
    val sessionDetail: LiveData<SessionDetailUi?> = _sessionDetail

    // Status loading
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    init {
        loadSessions()
    }

    /**
     * Memuat daftar semua sesi beserta jumlah segmennya.
     */
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

    /**
     * Memuat detail lengkap sesi berdasarkan ID.
     */
    fun loadSessionDetail(sessionId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val session = surveyRepository.getSessionById(sessionId)
                if (session == null) {
                    Timber.w("Session with id $sessionId not found")
                    _sessionDetail.value = null
                    return@launch
                }

                val segments = surveyRepository.getSegmentsForSessionOnce(sessionId)

                val totalDistance = session.totalDistance
                val durationMinutes = ((session.endTime ?: session.startTime) - session.startTime) / 60000
                val avgConfidence = if (segments.isNotEmpty()) {
                    (segments.map { it.confidence }.average()).roundToInt()
                } else 0

                val photoCount = segments.count { !it.photoPath.isNullOrBlank() }
                val audioCount = segments.count { !it.audioPath.isNullOrBlank() }

                val conditionDistribution = segments.groupBy { it.conditionAuto }
                    .mapValues { (_, segs) -> segs.sumOf { it.endDistance - it.startDistance } }

                val surfaceDistribution = segments.groupBy { it.surfaceType }
                    .mapValues { (_, segs) -> segs.sumOf { it.endDistance - it.startDistance } }

                val detail = SessionDetailUi(
                    session = session,
                    segments = segments,
                    totalDistance = totalDistance,
                    durationMinutes = durationMinutes.toInt(),
                    avgConfidence = avgConfidence,
                    photoCount = photoCount,
                    audioCount = audioCount,
                    conditionDistribution = conditionDistribution,
                    surfaceDistribution = surfaceDistribution
                )
                _sessionDetail.value = detail
                Timber.d("Loaded detail for session $sessionId with ${segments.size} segments")
            } catch (e: Exception) {
                Timber.e(e, "Gagal memuat detail sesi $sessionId")
                _sessionDetail.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Menghapus sesi beserta semua segmen dan telemetri terkait.
     */
    fun deleteSession(session: SurveySession) {
        viewModelScope.launch {
            try {
                telemetryRepository.deleteBySession(session.id)
                surveyRepository.deleteSessionById(session.id)
                Timber.d("Deleted session ${session.id}")
                // Refresh daftar sesi
                loadSessions()
            } catch (e: Exception) {
                Timber.e(e, "Gagal menghapus sesi ${session.id}")
            }
        }
    }

    /**
     * Ekspor sesi ke format GPX (callback untuk fragment).
     */
    fun exportSessionToGpx(sessionId: Long, callback: (File?) -> Unit) {
        viewModelScope.launch {
            try {
                val session = surveyRepository.getSessionById(sessionId)
                if (session == null) {
                    Timber.w("Session $sessionId not found for GPX export")
                    callback(null)
                    return@launch
                }
                val telemetries = telemetryRepository.getTelemetryForSessionOnce(sessionId)
                if (telemetries.isEmpty()) {
                    Timber.w("No telemetry data for session $sessionId, cannot export GPX")
                    callback(null)
                    return@launch
                }
                val segments = surveyRepository.getSegmentsForSessionOnce(sessionId)
                val file = FileExporter.exportToGpx(
                    context,
                    "RoadSense_${session.startTime}",
                    telemetries,
                    segments
                )
                callback(file)
            } catch (e: Exception) {
                Timber.e(e, "Error exporting session $sessionId to GPX")
                callback(null)
            }
        }
    }

    /**
     * Ekspor sesi ke format CSV (callback untuk fragment).
     */
    fun exportSessionToCsv(sessionId: Long, callback: (File?) -> Unit) {
        viewModelScope.launch {
            try {
                val session = surveyRepository.getSessionById(sessionId)
                if (session == null) {
                    Timber.w("Session $sessionId not found for CSV export")
                    callback(null)
                    return@launch
                }
                val segments = surveyRepository.getSegmentsForSessionOnce(sessionId)
                val file = FileExporter.exportToCsv(
                    context,
                    "RoadSense_${session.startTime}",
                    session,
                    segments
                )
                callback(file)
            } catch (e: Exception) {
                Timber.e(e, "Error exporting session $sessionId to CSV")
                callback(null)
            }
        }
    }
}