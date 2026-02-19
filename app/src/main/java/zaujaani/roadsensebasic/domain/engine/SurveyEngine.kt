package zaujaani.roadsensebasic.domain.engine

import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zaujaani.roadsensebasic.data.local.entity.Condition
import zaujaani.roadsensebasic.data.local.entity.EventType
import zaujaani.roadsensebasic.data.local.entity.RoadEvent
import zaujaani.roadsensebasic.data.local.entity.Surface
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.local.entity.TelemetryRaw
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.data.repository.TelemetryRepository
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.util.Constants
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine utama survey jalan.
 * Singleton agar state tetap ada saat fragment berganti.
 */
@Singleton
class SurveyEngine @Inject constructor(
    private val sensorGateway: SensorGateway,
    private val telemetryRepository: TelemetryRepository,
    private val surveyRepository: SurveyRepository,
    private val vibrationAnalyzer: VibrationAnalyzer,
    private val confidenceCalculator: ConfidenceCalculator
) {
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentSessionId: Long? = null
    private var isActive = false
    private var currentDistanceValue = 0.0
    private var lastLocation: Location? = null
    private val telemetryBuffer = mutableListOf<TelemetryRaw>()
    private var lastSaveTime = System.currentTimeMillis()

    private var currentCondition: Condition = Condition.BAIK
    private var currentSurface: Surface = Surface.ASPAL

    // ── StateFlows ─────────────────────────────────────────────────────────────

    /** Jarak kumulatif survey (meter). Diobservasi langsung oleh ViewModel/UI. */
    private val _currentDistance = MutableStateFlow(0.0)
    val currentDistance: StateFlow<Double> = _currentDistance.asStateFlow()

    private val _isSurveying = MutableStateFlow(false)
    val isSurveying: StateFlow<Boolean> = _isSurveying.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _currentVibration = MutableStateFlow(0f)
    val currentVibration: StateFlow<Float> = _currentVibration.asStateFlow()

    private val _vibrationHistory = MutableStateFlow<List<Float>>(emptyList())
    val vibrationHistory: StateFlow<List<Float>> = _vibrationHistory.asStateFlow()

    private val _distanceTrigger = MutableSharedFlow<Double>(extraBufferCapacity = 5)
    val distanceTrigger = _distanceTrigger.asSharedFlow()

    private val _roadName = MutableStateFlow("")
    val roadName: StateFlow<String> = _roadName.asStateFlow()

    // ── Accessors ──────────────────────────────────────────────────────────────

    fun getCurrentSessionId(): Long? = currentSessionId
    fun getCurrentDistance(): Double = currentDistanceValue
    fun getLastLocation(): Location? = lastLocation
    fun getCurrentCondition(): Condition = currentCondition
    fun getCurrentSurface(): Surface = currentSurface

    // ── Session Management ─────────────────────────────────────────────────────

    suspend fun startNewSession(surveyorName: String = "", roadName: String = ""): Long {
        _roadName.value = roadName
        val firstLoc = lastLocation
        val session = SurveySession(
            startTime = System.currentTimeMillis(),
            deviceModel = android.os.Build.MODEL,
            surveyorName = surveyorName,
            roadName = roadName,
            startLat = firstLoc?.latitude ?: 0.0,
            startLng = firstLoc?.longitude ?: 0.0
        )
        val id = surveyRepository.insertSession(session)
        currentSessionId = id
        isActive = true
        currentDistanceValue = 0.0
        _currentDistance.value = 0.0
        _isSurveying.value = true
        _isPaused.value = false
        _vibrationHistory.value = emptyList()
        lastLocation = null
        telemetryBuffer.clear()
        lastSaveTime = System.currentTimeMillis()
        currentCondition = Condition.BAIK
        currentSurface = Surface.ASPAL
        return id
    }

    suspend fun endCurrentSession() {
        if (!isActive) return
        flushTelemetrySync()
        val sessionId = currentSessionId ?: return
        val session = surveyRepository.getSessionById(sessionId)
        if (session != null) {
            val lastLoc = lastLocation
            surveyRepository.updateSession(
                session.copy(
                    endTime = System.currentTimeMillis(),
                    totalDistance = currentDistanceValue,
                    endLat = lastLoc?.latitude ?: session.startLat,
                    endLng = lastLoc?.longitude ?: session.startLng,
                    avgConfidence = 0
                )
            )
        }
        resetState()
    }

    fun discardCurrentSession() {
        resetState()
    }

    private fun resetState() {
        isActive = false
        currentSessionId = null
        currentDistanceValue = 0.0
        _currentDistance.value = 0.0
        _isSurveying.value = false
        _isPaused.value = false
        lastLocation = null
        telemetryBuffer.clear()
        _vibrationHistory.value = emptyList()
        _currentVibration.value = 0f
    }

    fun pauseSurvey() {
        if (!isActive) return
        _isPaused.value = true
    }

    fun resumeSurvey() {
        if (!isActive) return
        _isPaused.value = false
    }

    // ── Location Update ────────────────────────────────────────────────────────

    fun updateLocation(location: Location) {
        if (!isActive || _isPaused.value) return

        // Hitung jarak dari lokasi sebelumnya
        lastLocation?.let { last ->
            val results = FloatArray(1)
            Location.distanceBetween(
                last.latitude, last.longitude,
                location.latitude, location.longitude,
                results
            )
            val delta = results[0]
            // Filter noise: hanya update jika bergerak > 0.5m dan akurasi cukup baik
            if (delta > 0.5f && location.accuracy < Constants.GPS_ACCURACY_THRESHOLD) {
                val previousDistance = currentDistanceValue
                currentDistanceValue += delta
                _currentDistance.value = currentDistanceValue

                // Trigger setiap kelipatan 100m
                val previous100 = (previousDistance / 100).toInt()
                val current100 = (currentDistanceValue / 100).toInt()
                if (current100 > previous100) {
                    engineScope.launch {
                        _distanceTrigger.emit(currentDistanceValue)
                    }
                }
            }
        }
        lastLocation = location

        // Baca sensor terkini
        val vibrationZ = sensorGateway.getLatestVibration()
        val vibrationX = sensorGateway.axisX.value
        val vibrationY = sensorGateway.axisY.value

        _currentVibration.value = vibrationZ

        // Update chart history (bounded list)
        val updatedHistory = _vibrationHistory.value.toMutableList().also {
            it.add(vibrationZ)
            if (it.size > Constants.VIBRATION_HISTORY_MAX) it.removeAt(0)
        }
        _vibrationHistory.value = updatedHistory

        // Simpan telemetri ke buffer
        val sessionId = currentSessionId ?: return
        val telemetry = TelemetryRaw(
            sessionId = sessionId,
            timestamp = Instant.now(),
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            speed = location.speed,
            vibrationX = vibrationX,
            vibrationY = vibrationY,
            vibrationZ = vibrationZ,
            gpsAccuracy = location.accuracy,
            cumulativeDistance = currentDistanceValue,
            condition = currentCondition,
            surface = currentSurface
        )
        telemetryBuffer.add(telemetry)

        val now = System.currentTimeMillis()
        if (telemetryBuffer.size >= Constants.TELEMETRY_BUFFER_SIZE ||
            now - lastSaveTime > Constants.TELEMETRY_FLUSH_INTERVAL
        ) {
            flushTelemetry()
            lastSaveTime = now
        }
    }

    fun flushTelemetry() {
        if (telemetryBuffer.isEmpty()) return
        val snapshot = telemetryBuffer.toList()
        telemetryBuffer.clear()
        engineScope.launch {
            try {
                telemetryRepository.insertAll(snapshot)
            } catch (e: Exception) {
                // Kembalikan ke buffer jika gagal
                synchronized(telemetryBuffer) {
                    telemetryBuffer.addAll(0, snapshot)
                }
            }
        }
    }

    private suspend fun flushTelemetrySync() {
        if (telemetryBuffer.isEmpty()) return
        val snapshot = telemetryBuffer.toList()
        telemetryBuffer.clear()
        try {
            telemetryRepository.insertAll(snapshot)
        } catch (_: Exception) { /* diabaikan saat stop */ }
    }

    // ── Event Recording ────────────────────────────────────────────────────────

    fun recordConditionChange(condition: Condition) {
        currentCondition = condition
        recordEvent(EventType.CONDITION_CHANGE, condition.name)
    }

    fun recordSurfaceChange(surface: Surface) {
        currentSurface = surface
        recordEvent(EventType.SURFACE_CHANGE, surface.name)
    }

    fun recordPhoto(path: String, notes: String? = null) {
        recordEvent(EventType.PHOTO, path, notes)
    }

    fun recordVoice(path: String, notes: String? = null) {
        recordEvent(EventType.VOICE, path, notes)
    }

    /** Helper untuk menghindari duplikasi kode event recording */
    private fun recordEvent(type: EventType, value: String, notes: String? = null) {
        val location = lastLocation ?: return
        val sessionId = currentSessionId ?: return
        val event = RoadEvent(
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            latitude = location.latitude,
            longitude = location.longitude,
            distance = currentDistanceValue.toFloat(),
            eventType = type,
            value = value,
            notes = notes
        )
        engineScope.launch { surveyRepository.insertEvent(event) }
    }

    // ── Utility ────────────────────────────────────────────────────────────────

    fun checkConditionConsistency(selectedCondition: Condition): Boolean {
        val vib = _currentVibration.value
        return when (selectedCondition) {
            Condition.BAIK -> vib < Constants.DEFAULT_THRESHOLD_BAIK * 1.2f
            Condition.SEDANG -> vib in
                    (Constants.DEFAULT_THRESHOLD_BAIK * 0.8f)..(Constants.DEFAULT_THRESHOLD_SEDANG * 1.2f)
            Condition.RUSAK_RINGAN -> vib in
                    (Constants.DEFAULT_THRESHOLD_SEDANG * 0.8f)..(Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN * 1.2f)
            Condition.RUSAK_BERAT -> vib > Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN * 0.8f
        }
    }
}