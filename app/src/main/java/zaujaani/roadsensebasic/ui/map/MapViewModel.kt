package zaujaani.roadsensebasic.ui.map

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.domain.engine.SurveyEngine
import zaujaani.roadsensebasic.gateway.GPSGateway
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.service.SurveyForegroundService
import zaujaani.roadsensebasic.util.Constants
import zaujaani.roadsensebasic.util.PreferencesManager
import javax.inject.Inject

/**
 * ViewModel untuk MapFragment.
 *
 * State machine survey:
 * IDLE → SURVEYING → PAUSED → SURVEYING → STOPPED
 *
 * GPS dan sensor dikelola oleh SurveyForegroundService (tidak terganggu lifecycle fragment).
 * ViewModel hanya subscribe ke SurveyEngine StateFlows untuk update UI.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gpsGateway: GPSGateway,
    private val sensorGateway: SensorGateway,
    private val surveyEngine: SurveyEngine,
    private val surveyRepository: SurveyRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    // ========== LOCATION & SENSOR UI STATE ==========
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    private val _speed = MutableStateFlow(0f)
    val speed: StateFlow<Float> = _speed

    private val _gpsAccuracy = MutableStateFlow(0f)
    val gpsAccuracy: StateFlow<Float> = _gpsAccuracy

    private val _distance = MutableStateFlow(0.0)
    val distance: StateFlow<Double> = _distance

    // Vibration dari engine (sudah terfilter)
    val vibration: StateFlow<Float> = surveyEngine.currentVibration

    // ========== SURVEY STATE (dari SurveyEngine singleton) ==========
    val isSurveying: StateFlow<Boolean> = surveyEngine.isSurveying
    val isPaused: StateFlow<Boolean> = surveyEngine.isPausedFlow
    val isSegmentStarted: StateFlow<Boolean> = surveyEngine.isSegmentActive
    val vibrationHistory: StateFlow<List<Float>> = surveyEngine.vibrationHistory

    // ========== THRESHOLDS ==========
    private val _thresholdBaik = MutableStateFlow(Constants.DEFAULT_THRESHOLD_BAIK)
    val thresholdBaik: StateFlow<Float> = _thresholdBaik

    private val _thresholdSedang = MutableStateFlow(Constants.DEFAULT_THRESHOLD_SEDANG)
    val thresholdSedang: StateFlow<Float> = _thresholdSedang

    private val _thresholdRusakRingan = MutableStateFlow(Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN)
    val thresholdRusakRingan: StateFlow<Float> = _thresholdRusakRingan

    // ========== TEMPORARY SELECTION ==========
    private val _tempCondition = MutableStateFlow(Constants.CONDITION_BAIK)
    val tempCondition: StateFlow<String> = _tempCondition

    private val _tempSurface = MutableStateFlow(Constants.SURFACE_TYPES.first())
    val tempSurface: StateFlow<String> = _tempSurface

    // ========== REALTIME ASSESSMENT ==========
    private val _currentAutoCondition = MutableStateFlow(Constants.CONDITION_BAIK)
    val currentAutoCondition: StateFlow<String> = _currentAutoCondition

    private val _currentConfidence = MutableStateFlow(0)
    val currentConfidence: StateFlow<Int> = _currentConfidence

    // ========== LOCATION TRACKING (untuk UI peta saja) ==========
    private var locationTrackingJob: Job? = null

    init {
        viewModelScope.launch {
            preferencesManager.thresholdBaik.collect { _thresholdBaik.value = it }
        }
        viewModelScope.launch {
            preferencesManager.thresholdSedang.collect { _thresholdSedang.value = it }
        }
        viewModelScope.launch {
            preferencesManager.thresholdRusakRingan.collect { _thresholdRusakRingan.value = it }
        }

        // Update penilaian otomatis saat getaran berubah (dari engine)
        surveyEngine.currentVibration
            .onEach { _ ->
                if (surveyEngine.isSurveying.value && !surveyEngine.isPausedFlow.value) {
                    _currentAutoCondition.value = surveyEngine.getCurrentConditionAuto()
                    _currentConfidence.value = surveyEngine.getCurrentConfidence()
                }
            }
            .launchIn(viewModelScope)

        // Subscribe ke jarak dari engine
        surveyEngine.currentDistance
            .onEach { _distance.value = it }
            .launchIn(viewModelScope)
    }

    // ========== LOCATION TRACKING ==========
    fun startLocationTracking() {
        if (locationTrackingJob?.isActive == true) return
        locationTrackingJob = gpsGateway.getLocationFlow()
            .catch { e ->
                Timber.e(e, "Error in location flow")
                // Bisa emit nilai default atau handle error
            }
            .onEach { location ->
                _location.value = location
                _speed.value = location.speed
                _gpsAccuracy.value = location.accuracy
            }
            .launchIn(viewModelScope)
    }

    fun stopLocationTracking() {
        locationTrackingJob?.cancel()
        locationTrackingJob = null
    }

    // ========== SURVEY LIFECYCLE ==========
    fun startSurvey(surveyorName: String = "", roadName: String = "") {
        viewModelScope.launch {
            surveyEngine.startNewSession(surveyorName, roadName)
            _distance.value = 0.0
            startForegroundService()
            Timber.d("Survey started: surveyor=$surveyorName, road=$roadName")
        }
    }

    fun pauseSurvey() {
        surveyEngine.pauseSurvey()
        sendServiceCommand(Constants.ACTION_PAUSE)
        Timber.d("Survey paused")
    }

    fun resumeSurvey() {
        surveyEngine.resumeSurvey()
        sendServiceCommand(Constants.ACTION_RESUME)
        Timber.d("Survey resumed")
    }

    fun stopSurveyAndSave() {
        viewModelScope.launch {
            surveyEngine.endCurrentSession()
            stopForegroundService()
            _distance.value = 0.0
            Timber.d("Survey stopped and saved")
        }
    }

    fun stopSurveyAndDiscard() {
        viewModelScope.launch {
            val sessionId = surveyEngine.getCurrentSessionId()
            if (sessionId != null) {
                surveyRepository.deleteSessionById(sessionId)
                Timber.d("Session $sessionId discarded")
            }
            surveyEngine.discardCurrentSession()
            stopForegroundService()
            _distance.value = 0.0
        }
    }

    // ========== SEGMENT MANAGEMENT ==========
    fun startSegment() {
        if (!surveyEngine.isSurveying.value || surveyEngine.isPausedFlow.value) {
            Timber.w("Cannot start segment: surveying=${surveyEngine.isSurveying.value}, paused=${surveyEngine.isPausedFlow.value}")
            return
        }
        surveyEngine.startSegment()
        Timber.d("Segment started")
    }

    fun endSegment(navController: NavController) {
        if (!surveyEngine.isSegmentActive.value) {
            Timber.w("No active segment to end")
            return
        }

        val sessionId = surveyEngine.getCurrentSessionId() ?: run {
            Timber.e("Cannot end segment: no active session")
            return
        }

        val startDist = surveyEngine.getSegmentStartDistance().toFloat()
        val endDist = surveyEngine.getCurrentDistance().toFloat()
        val avgVibration = surveyEngine.getCurrentAvgVibration().toFloat()
        val conditionAuto = surveyEngine.getCurrentConditionAuto()
        val confidence = surveyEngine.getCurrentConfidence()

        val action = MapFragmentDirections.actionMapFragmentToSegmentBottomSheet(
            sessionId = sessionId,
            startDistance = startDist,
            endDistance = endDist,
            avgVibration = avgVibration,
            conditionAuto = conditionAuto,
            confidence = confidence,
            tempCondition = _tempCondition.value,
            tempSurface = _tempSurface.value
        )
        navController.navigate(action)
        Timber.d("Navigated to segment bottom sheet for session $sessionId")
        // Segment akan direset setelah bottom sheet dismiss (di engine via cancel atau end)
    }

    fun cancelSegment() {
        surveyEngine.cancelSegment()
        Timber.d("Segment cancelled")
    }

    // ========== SETTERS ==========
    fun setTemporaryCondition(condition: String) {
        _tempCondition.value = condition
        Timber.d("Temporary condition set to $condition")
    }

    fun setTemporarySurface(surface: String) {
        _tempSurface.value = surface
        Timber.d("Temporary surface set to $surface")
    }

    // ========== FOREGROUND SERVICE ==========
    private fun startForegroundService() {
        val intent = Intent(context, SurveyForegroundService::class.java).apply {
            action = Constants.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Timber.d("Foreground service start command sent")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start foreground service")
        }
    }

    private fun stopForegroundService() {
        sendServiceCommand(Constants.ACTION_STOP)
    }

    private fun sendServiceCommand(action: String) {
        val intent = Intent(context, SurveyForegroundService::class.java).apply {
            this.action = action
        }
        try {
            context.startService(intent)
            Timber.d("Service command sent: $action")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send service command: $action")
        }
    }

    override fun onCleared() {
        stopLocationTracking()
        super.onCleared()
        Timber.d("MapViewModel cleared")
    }
}