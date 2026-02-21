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
import zaujaani.roadsensebasic.data.local.entity.SurveyMode
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.domain.engine.SurveyEngine
import zaujaani.roadsensebasic.domain.model.LocationData
import zaujaani.roadsensebasic.gateway.GPSGateway
import zaujaani.roadsensebasic.service.SurveyForegroundService
import zaujaani.roadsensebasic.util.Constants
import zaujaani.roadsensebasic.util.PreferencesManager
import javax.inject.Inject

/**
 * MapViewModel — UI state hanya.
 *
 * FIX KRITIS: ViewModel TIDAK memanggil surveyEngine.updateLocation().
 * Hanya SurveyForegroundService yang boleh feed data ke engine.
 * ViewModel hanya mengambil lokasi untuk tampilan peta & UI.
 * Sebelumnya, keduanya memanggil updateLocation → jarak dihitung 2x lipat!
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gpsGateway: GPSGateway,
    private val surveyEngine: SurveyEngine,
    private val surveyRepository: SurveyRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    // ── Location & Speed (UI display only) ────────────────────────────────
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val _speed = MutableStateFlow(0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    // ── Data dari engine (single source of truth) ─────────────────────────
    val distance: StateFlow<Double> = surveyEngine.currentDistance
    val vibration: StateFlow<Float> = surveyEngine.currentVibration

    // ── Survey State ──────────────────────────────────────────────────────
    val isSurveying: StateFlow<Boolean> = surveyEngine.isSurveying
    val isPaused: StateFlow<Boolean> = surveyEngine.isPaused
    val vibrationHistory: StateFlow<List<Float>> = surveyEngine.vibrationHistory
    val roadName: StateFlow<String> = surveyEngine.roadName
    val mode: StateFlow<SurveyMode> = surveyEngine.mode

    // ── Condition & Surface ───────────────────────────────────────────────
    private val _currentCondition = MutableStateFlow(Condition.BAIK)
    val currentCondition: StateFlow<Condition> = _currentCondition.asStateFlow()

    private val _currentSurface = MutableStateFlow(Surface.ASPAL)
    val currentSurface: StateFlow<Surface> = _currentSurface.asStateFlow()

    // ── Thresholds (dari Preferences) ─────────────────────────────────────
    private val _thresholdBaik = MutableStateFlow(Constants.DEFAULT_THRESHOLD_BAIK)
    val thresholdBaik: StateFlow<Float> = _thresholdBaik.asStateFlow()

    private val _thresholdSedang = MutableStateFlow(Constants.DEFAULT_THRESHOLD_SEDANG)
    val thresholdSedang: StateFlow<Float> = _thresholdSedang.asStateFlow()

    private val _thresholdRusakRingan = MutableStateFlow(Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN)
    val thresholdRusakRingan: StateFlow<Float> = _thresholdRusakRingan.asStateFlow()

    // ── Trigger jarak (dari engine) ──────────────────────────────────────
    private val _distanceTrigger = MutableSharedFlow<Double>(extraBufferCapacity = 5)
    val distanceTrigger = _distanceTrigger.asSharedFlow()

    // ── Voice recording state (untuk toggle FAB) ─────────────────────────
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var locationTrackingJob: Job? = null

    init {
        collectPreferences()
        collectDistanceTrigger()
    }

    private fun collectPreferences() {
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

    private fun collectDistanceTrigger() {
        viewModelScope.launch {
            surveyEngine.distanceTrigger.collect { dist ->
                _distanceTrigger.emit(dist)
            }
        }
    }

    /**
     * Location tracking HANYA untuk UI display (peta, kecepatan).
     * TIDAK memanggil surveyEngine.updateLocation() — itu tugas ForegroundService.
     * Ini menghilangkan bug double-counting jarak.
     */
    fun startLocationTracking() {
        if (locationTrackingJob?.isActive == true) return
        locationTrackingJob = gpsGateway.getLocationFlow()
            .catch { e -> Timber.e(e, "Error in location flow (UI)") }
            .onEach { loc ->
                _location.value = loc
                _speed.value = loc.speed
                // ✅ TIDAK panggil surveyEngine.updateLocation() di sini!
                // ForegroundService yang menangani feed ke engine.
            }
            .launchIn(viewModelScope)
    }

    fun stopLocationTracking() {
        locationTrackingJob?.cancel()
        locationTrackingJob = null
    }

    // ── Survey Lifecycle ─────────────────────────────────────────────────
    fun startSurvey(surveyorName: String, roadName: String, mode: SurveyMode) {
        viewModelScope.launch {
            surveyEngine.startNewSession(surveyorName, roadName, mode)
            _currentCondition.value = Condition.BAIK
            _currentSurface.value = Surface.ASPAL
            startForegroundService()
        }
    }

    fun pauseSurvey() {
        surveyEngine.pauseSurvey()
        sendServiceCommand(Constants.ACTION_PAUSE)
    }

    fun resumeSurvey() {
        surveyEngine.resumeSurvey()
        sendServiceCommand(Constants.ACTION_RESUME)
    }

    fun stopSurveyAndSave() {
        viewModelScope.launch {
            surveyEngine.endCurrentSession()
            stopForegroundService()
        }
    }

    fun stopSurveyAndDiscard() {
        viewModelScope.launch {
            surveyEngine.getCurrentSessionId()?.let { id ->
                surveyRepository.deleteSessionById(id)
            }
            surveyEngine.discardCurrentSession()
            stopForegroundService()
        }
    }

    // ── Event Recording ──────────────────────────────────────────────────
    fun recordCondition(condition: Condition) {
        surveyEngine.recordConditionChange(condition)
        _currentCondition.value = condition
    }

    fun recordSurface(surface: Surface) {
        surveyEngine.recordSurfaceChange(surface)
        _currentSurface.value = surface
    }

    fun recordPhoto(path: String, distance: Double, lat: Double, lng: Double, notes: String? = null) {
        surveyEngine.recordPhoto(path, notes)
    }

    fun recordVoice(path: String, notes: String? = null) {
        surveyEngine.recordVoice(path, notes)
    }

    fun setRecordingState(recording: Boolean) {
        _isRecording.value = recording
    }

    // ── Validation ───────────────────────────────────────────────────────
    fun checkConditionConsistency(condition: Condition): Boolean =
        surveyEngine.checkConditionConsistency(condition)

    // ── Helpers untuk MediaManager ────────────────────────────────────────
    fun getCurrentDistance(): Double = surveyEngine.getCurrentDistance()
    fun getCurrentLat(): Double = surveyEngine.getLastLocation()?.latitude ?: 0.0
    fun getCurrentLng(): Double = surveyEngine.getLastLocation()?.longitude ?: 0.0
    fun getCurrentRoadName(): String = surveyEngine.roadName.value

    // ── Foreground Service ───────────────────────────────────────────────
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
        } catch (e: Exception) {
            Timber.e(e, "Failed to start foreground service")
        }
    }

    private fun stopForegroundService() {
        sendServiceCommand(Constants.ACTION_STOP)
    }

    private fun sendServiceCommand(action: String) {
        try {
            context.startService(
                Intent(context, SurveyForegroundService::class.java).apply {
                    this.action = action
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to send service command: $action")
        }
    }

    override fun onCleared() {
        stopLocationTracking()
        super.onCleared()
    }
}