package zaujaani.roadsensebasic.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.domain.engine.SurveyEngine
import zaujaani.roadsensebasic.gateway.GPSGateway
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.util.Constants
import zaujaani.roadsensebasic.util.PreferencesManager
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    private val gpsGateway: GPSGateway,
    private val sensorGateway: SensorGateway,
    private val surveyEngine: SurveyEngine,
    private val surveyRepository: SurveyRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _location = MutableStateFlow<android.location.Location?>(null)
    val location: StateFlow<android.location.Location?> = _location

    private val _vibration = MutableStateFlow(0f)
    val vibration: StateFlow<Float> = _vibration

    private val _speed = MutableStateFlow(0f)
    val speed: StateFlow<Float> = _speed

    private val _distance = MutableStateFlow(0.0)
    val distance: StateFlow<Double> = _distance

    private val _gpsAccuracy = MutableStateFlow(0f)
    val gpsAccuracy: StateFlow<Float> = _gpsAccuracy

    private val _isSurveying = MutableStateFlow(false)
    val isSurveying: StateFlow<Boolean> = _isSurveying

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    private val _vibrationHistory = MutableStateFlow<List<Float>>(emptyList())
    val vibrationHistory: StateFlow<List<Float>> = _vibrationHistory

    private val _thresholdBaik = MutableStateFlow(0.3f)
    val thresholdBaik: StateFlow<Float> = _thresholdBaik

    private val _thresholdSedang = MutableStateFlow(0.6f)
    val thresholdSedang: StateFlow<Float> = _thresholdSedang

    private val _thresholdRusakRingan = MutableStateFlow(1.0f)
    val thresholdRusakRingan: StateFlow<Float> = _thresholdRusakRingan

    private val _isSegmentStarted = MutableStateFlow(false)
    val isSegmentStarted: StateFlow<Boolean> = _isSegmentStarted

    // State untuk pilihan sementara
    private val _tempCondition = MutableStateFlow(Constants.CONDITION_BAIK)
    val tempCondition: StateFlow<String> = _tempCondition

    private val _tempSurface = MutableStateFlow(Constants.SURFACE_TYPES.first())
    val tempSurface: StateFlow<String> = _tempSurface

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
    }

    fun setTemporaryCondition(condition: String) {
        _tempCondition.value = condition
    }

    fun setTemporarySurface(surface: String) {
        _tempSurface.value = surface
    }

    fun startLocationTracking() {
        stopLocationTracking()
        locationTrackingJob = combine(
            gpsGateway.getLocationFlow(),
            sensorGateway.vibrationFlow
        ) { location, vibration ->
            location to vibration
        }.onEach { (location, vibration) ->
            _location.value = location
            _vibration.value = vibration
            _speed.value = location.speed
            _gpsAccuracy.value = location.accuracy

            if (_isSurveying.value && !_isPaused.value) {
                surveyEngine.updateLocation(location)
                _distance.value = surveyEngine.getCurrentDistance()
            }

            val currentList = _vibrationHistory.value.toMutableList()
            currentList.add(vibration)
            if (currentList.size > 100) currentList.removeAt(0)
            _vibrationHistory.value = currentList
        }.launchIn(viewModelScope)
    }

    fun stopLocationTracking() {
        locationTrackingJob?.cancel()
        locationTrackingJob = null
    }

    fun startSurvey() {
        viewModelScope.launch {
            surveyEngine.startNewSession()
            _isSurveying.value = true
            _isPaused.value = false
            _distance.value = 0.0
            _isSegmentStarted.value = false
        }
    }

    fun pauseSurvey() {
        if (_isSurveying.value && !_isPaused.value) {
            _isPaused.value = true
        }
    }

    fun resumeSurvey() {
        if (_isSurveying.value && _isPaused.value) {
            _isPaused.value = false
        }
    }

    fun stopSurveyAndSave() {
        viewModelScope.launch {
            surveyEngine.endCurrentSession()
            _isSurveying.value = false
            _isPaused.value = false
            _isSegmentStarted.value = false
        }
    }

    fun stopSurveyAndDiscard() {
        viewModelScope.launch {
            val sessionId = surveyEngine.getCurrentSessionId()
            if (sessionId != null) {
                surveyRepository.deleteSessionById(sessionId)
            }
            surveyEngine.discardCurrentSession()
            _isSurveying.value = false
            _isPaused.value = false
            _isSegmentStarted.value = false
            _distance.value = 0.0
        }
    }

    fun startSegment() {
        if (!_isSurveying.value || _isPaused.value) return
        surveyEngine.startSegment()
        _isSegmentStarted.value = true
    }

    fun endSegment(navController: NavController) {
        if (!_isSegmentStarted.value) return

        val sessionId = surveyEngine.getCurrentSessionId() ?: return
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

        _isSegmentStarted.value = false
    }

    override fun onCleared() {
        stopLocationTracking()
        super.onCleared()
    }
}