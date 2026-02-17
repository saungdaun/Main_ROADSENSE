package zaujaani.roadsensebasic.ui.summary

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.repository.SessionWithCount
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.data.repository.TelemetryRepository
import zaujaani.roadsensebasic.util.FileExporter
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val surveyRepository: SurveyRepository,
    private val telemetryRepository: TelemetryRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _sessions = MutableLiveData<List<SessionWithCount>>()
    val sessions: LiveData<List<SessionWithCount>> = _sessions

    private val _isLoading = MutableLiveData(true)
    val isLoading: LiveData<Boolean> = _isLoading

    init {
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            _isLoading.value = true
            surveyRepository.getSessionsWithCount().collect { list ->
                _sessions.value = list
                _isLoading.value = false
            }
        }
    }

    fun deleteSession(session: SurveySession) {
        viewModelScope.launch {
            surveyRepository.deleteSessionById(session.id)
            loadSessions() // refresh
        }
    }

    fun exportSessionToGpx(sessionId: Long, callback: (File?) -> Unit) {
        viewModelScope.launch {
            val telemetries = telemetryRepository.getTelemetryForSessionOnce(sessionId)
            val segments = surveyRepository.getSegmentsForSessionOnce(sessionId)
            val session = surveyRepository.getSessionById(sessionId)

            if (session == null || telemetries.isEmpty()) {
                callback(null)
                return@launch
            }

            val fileName = "RoadSense_${session.startTime}"
            val file = FileExporter.exportToGpx(context, fileName, telemetries, segments)
            callback(file)
        }
    }
}