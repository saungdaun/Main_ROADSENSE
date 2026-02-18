package zaujaani.roadsensebasic.ui.map

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsensebasic.data.local.entity.Condition
import zaujaani.roadsensebasic.data.local.entity.Surface
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.domain.engine.SurveyEngine
import zaujaani.roadsensebasic.gateway.GPSGateway
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.service.SurveyForegroundService
import zaujaani.roadsensebasic.util.Constants
import zaujaani.roadsensebasic.util.PreferencesManager
import javax.inject.Inject

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
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val _speed = MutableStateFlow(0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _gpsAccuracy = MutableStateFlow(0f)
    val gpsAccuracy: StateFlow<Float> = _gpsAccuracy.asStateFlow()

    private val _distance = MutableStateFlow(0.0)
    val distance: StateFlow<Double> = _distance.asStateFlow()

    // Vibration dari engine (sudah terfilter)
    val vibration: StateFlow<Float> = surveyEngine.currentVibration

    // ========== SURVEY STATE (dari SurveyEngine) ==========
    val isSurveying: StateFlow<Boolean> = surveyEngine.isSurveying
    val isPaused: StateFlow<Boolean> = surveyEngine.isPaused
    val vibrationHistory: StateFlow<List<Float>> = surveyEngine.vibrationHistory

    // ========== CONDITION & SURFACE (dari engine) ==========
    val currentCondition: StateFlow<Condition> = MutableStateFlow(Condition.BAIK) // Akan diupdate
    val currentSurface: StateFlow<Surface> = MutableStateFlow(Surface.ASPAL)

    // ========== THRESHOLDS ==========
    private val _thresholdBaik = MutableStateFlow(Constants.DEFAULT_THRESHOLD_BAIK)
    val thresholdBaik: StateFlow<Float> = _thresholdBaik.asStateFlow()

    private val _thresholdSedang = MutableStateFlow(Constants.DEFAULT_THRESHOLD_SEDANG)
    val thresholdSedang: StateFlow<Float> = _thresholdSedang.asStateFlow()

    private val _thresholdRusakRingan = MutableStateFlow(Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN)
    val thresholdRusakRingan: StateFlow<Float> = _thresholdRusakRingan.asStateFlow()

    // ========== TRIGGER JARAK ==========
    private val _distanceTrigger = MutableSharedFlow<Double>(extraBufferCapacity = 5)
    val distanceTrigger = _distanceTrigger.asSharedFlow()

    // ========== LOCATION TRACKING ==========
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

        // Update kondisi dan surface dari engine (perlu ditambahkan di engine nanti)
        // Untuk sementara, kita bisa set nilai default. Nanti engine akan punya state untuk kondisi dan surface.
        // Atau kita bisa mengambil dari event terakhir? Namun untuk sederhana, kita pakai state di viewmodel sendiri.
        // Kita akan update dari engine nanti setelah ada mekanisme.
    }

    // ========== LOCATION TRACKING ==========
    fun startLocationTracking() {
        if (locationTrackingJob?.isActive == true) return
        locationTrackingJob = gpsGateway.getLocationFlow()
            .catch { e ->
                Timber.e(e, "Error in location flow")
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

    // ========== EVENT RECORDING ==========
    fun recordCondition(condition: Condition) {
        surveyEngine.recordConditionChange(condition)
        // Update local state jika perlu
        (currentCondition as MutableStateFlow).value = condition
        Timber.d("Condition recorded: $condition")
    }

    fun recordSurface(surface: Surface) {
        surveyEngine.recordSurfaceChange(surface)
        (currentSurface as MutableStateFlow).value = surface
        Timber.d("Surface recorded: $surface")
    }

    fun recordPhoto(path: String, notes: String? = null) {
        surveyEngine.recordPhoto(path, notes)
        Timber.d("Photo recorded: $path")
    }

    fun recordVoice(path: String, notes: String? = null) {
        surveyEngine.recordVoice(path, notes)
        Timber.d("Voice recorded: $path")
    }

    // ========== VALIDASI KONSISTENSI ==========
    fun checkConditionConsistency(condition: Condition): Boolean {
        return surveyEngine.checkConditionConsistency(condition)
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