package zaujaani.roadsensebasic.domain.engine

import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zaujaani.roadsensebasic.data.local.entity.Condition
import zaujaani.roadsensebasic.data.local.entity.RoadSegment
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
 * Mengelola state survey, telemetri GPS, analisis getaran, dan segmen ruas.
 * Singleton sehingga state tetap ada saat fragment berganti.
 */
@Singleton
class SurveyEngine @Inject constructor(
    private val sensorGateway: SensorGateway,
    private val telemetryRepository: TelemetryRepository,
    private val surveyRepository: SurveyRepository,
    private val vibrationAnalyzer: VibrationAnalyzer,
    private val confidenceCalculator: ConfidenceCalculator
) {
    // Scope terpisah agar tidak tergantung lifecycle fragment
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentSessionId: Long? = null
    private var isActive = false
    private var isPaused = false
    private var currentDistanceValue = 0.0
    private var lastLocation: Location? = null
    private val telemetryBuffer = mutableListOf<TelemetryRaw>()
    private var lastSaveTime = System.currentTimeMillis()

    // State sementara untuk label kondisi dan permukaan (diperbarui oleh UI)
    private var currentTempCondition: Condition = Condition.BAIK
    private var currentTempSurface: Surface = Surface.ASPAL

    // StateFlows yang bisa diobservasi dari ViewModel manapun
    private val _currentDistance = MutableStateFlow(0.0)
    val currentDistance: StateFlow<Double> = _currentDistance.asStateFlow()

    private val _isSurveying = MutableStateFlow(false)
    val isSurveying: StateFlow<Boolean> = _isSurveying.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPausedFlow: StateFlow<Boolean> = _isPaused.asStateFlow()

    // State segmen aktif
    private var segmentStartDistance = 0.0
    private var segmentStartLocation: Location? = null
    private val segmentVibrationBuffer = mutableListOf<Float>()
    private var segmentStartTime = 0L
    private var isSegmentStarted = false

    private val _isSegmentActive = MutableStateFlow(false)
    val isSegmentActive: StateFlow<Boolean> = _isSegmentActive.asStateFlow()

    // Vibration realtime StateFlow
    private val _currentVibration = MutableStateFlow(0f)
    val currentVibration: StateFlow<Float> = _currentVibration.asStateFlow()

    // Riwayat vibration untuk chart (hanya saat survey aktif)
    private val _vibrationHistory = MutableStateFlow<List<Float>>(emptyList())
    val vibrationHistory: StateFlow<List<Float>> = _vibrationHistory.asStateFlow()

    // ========== STATE ACCESSORS ==========

    fun isSurveying(): Boolean = isActive
    fun isPausedState(): Boolean = isPaused
    fun getCurrentSessionId(): Long? = currentSessionId
    fun getCurrentDistance(): Double = currentDistanceValue
    fun getSegmentStartDistance(): Double = segmentStartDistance
    fun isSegmentStarted(): Boolean = isSegmentStarted

    // ========== LABEL MANAGEMENT (dipanggil dari UI) ==========
    fun setTempCondition(condition: Condition) {
        currentTempCondition = condition
    }

    fun setTempSurface(surface: Surface) {
        currentTempSurface = surface
    }

    // ========== SESSION MANAGEMENT ==========

    suspend fun startNewSession(surveyorName: String = "", roadName: String = ""): Long {
        val firstLoc = lastLocation
        val session = SurveySession(
            startTime = System.currentTimeMillis(),
            deviceModel = android.os.Build.MODEL,
            surveyorName = surveyorName,
            roadName = roadName,
            startLat = firstLoc?.latitude ?: 0.0,
            startLng = firstLoc?.longitude ?: 0.0
        )
        val id = surveyRepository.createSession(session)
        currentSessionId = id
        isActive = true
        isPaused = false
        currentDistanceValue = 0.0
        _currentDistance.value = 0.0
        _isSurveying.value = true
        _isPaused.value = false
        _vibrationHistory.value = emptyList()
        lastLocation = null
        telemetryBuffer.clear()
        resetSegment()
        return id
    }

    suspend fun endCurrentSession() {
        if (!isActive) return
        flushTelemetrySync()
        val sessionId = currentSessionId ?: return
        val session = surveyRepository.getSessionById(sessionId)
        if (session != null) {
            val lastLoc = lastLocation
            val updatedSession = session.copy(
                endTime = System.currentTimeMillis(),
                totalDistance = currentDistanceValue,
                endLat = lastLoc?.latitude ?: session.startLat,
                endLng = lastLoc?.longitude ?: session.startLng,
                avgConfidence = calculateAvgConfidence(sessionId)
            )
            surveyRepository.updateSession(updatedSession)
        }
        isActive = false
        isPaused = false
        _isSurveying.value = false
        _isPaused.value = false
        currentSessionId = null
    }

    fun discardCurrentSession() {
        isActive = false
        isPaused = false
        currentSessionId = null
        currentDistanceValue = 0.0
        _currentDistance.value = 0.0
        _isSurveying.value = false
        _isPaused.value = false
        lastLocation = null
        telemetryBuffer.clear()
        _vibrationHistory.value = emptyList()
        resetSegment()
    }

    fun pauseSurvey() {
        if (!isActive) return
        isPaused = true
        _isPaused.value = true
    }

    fun resumeSurvey() {
        if (!isActive) return
        isPaused = false
        _isPaused.value = false
    }

    private suspend fun calculateAvgConfidence(sessionId: Long): Int {
        val segments = surveyRepository.getSegmentsForSessionOnce(sessionId)
        return if (segments.isNotEmpty()) segments.map { it.confidence }.average().toInt() else 0
    }

    // ========== LOCATION UPDATE ==========

    fun updateLocation(location: Location) {
        if (!isActive || isPaused) return

        lastLocation?.let { last ->
            val results = FloatArray(1)
            Location.distanceBetween(
                last.latitude, last.longitude,
                location.latitude, location.longitude,
                results
            )
            // Filter noise: hanya update jika bergerak > 1m dan akurasi < 25m
            if (results[0] > 0.5f && location.accuracy < 25f) {
                currentDistanceValue += results[0]
                _currentDistance.value = currentDistanceValue
            }
        }
        lastLocation = location

        // Ambil nilai terkini dari sensor (Z sudah smoothed RMS, X dan Y raw)
        val vibrationZ = sensorGateway.getLatestVibration()
        val vibrationX = sensorGateway.axisX.value
        val vibrationY = sensorGateway.axisY.value

        _currentVibration.value = vibrationZ

        // Update chart history hanya dengan Z (untuk tampilan sederhana)
        val currentList = _vibrationHistory.value.toMutableList()
        currentList.add(vibrationZ)
        if (currentList.size > 150) currentList.removeAt(0)
        _vibrationHistory.value = currentList

        // Buat telemetri dengan data lengkap
        val telemetry = TelemetryRaw(
            sessionId = currentSessionId ?: return,
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
            condition = currentTempCondition,
            surface = currentTempSurface
        )
        telemetryBuffer.add(telemetry)

        val now = System.currentTimeMillis()
        if (telemetryBuffer.size >= 10 || now - lastSaveTime > 2000) {
            flushTelemetry()
            lastSaveTime = now
        }

        if (isSegmentStarted) {
            synchronized(segmentVibrationBuffer) {
                segmentVibrationBuffer.add(vibrationZ)
            }
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
                // Re-add ke buffer jika gagal
                telemetryBuffer.addAll(0, snapshot)
            }
        }
    }

    private suspend fun flushTelemetrySync() {
        if (telemetryBuffer.isEmpty()) return
        val snapshot = telemetryBuffer.toList()
        telemetryBuffer.clear()
        try {
            telemetryRepository.insertAll(snapshot)
        } catch (e: Exception) {
            // Diabaikan saat stop session
        }
    }

    // ========== SEGMENT MANAGEMENT ==========

    fun startSegment() {
        if (!isActive || isPaused) return
        synchronized(segmentVibrationBuffer) {
            segmentStartDistance = currentDistanceValue
            segmentStartLocation = lastLocation
            segmentVibrationBuffer.clear()
            segmentStartTime = System.currentTimeMillis()
            isSegmentStarted = true
            _isSegmentActive.value = true
        }
    }

    suspend fun endSegment(
        name: String,
        manualCondition: String?,
        surfaceType: String,
        notes: String,
        photoPath: String,
        audioPath: String
    ): RoadSegment? {
        if (!isSegmentStarted) return null

        val endDist = currentDistanceValue
        val endLocation = lastLocation
        val startLocation = segmentStartLocation

        // Buat salinan buffer untuk diproses
        val bufferCopy = synchronized(segmentVibrationBuffer) { segmentVibrationBuffer.toList() }

        val avgVibration = if (bufferCopy.isNotEmpty()) {
            vibrationAnalyzer.analyzeVibration(bufferCopy)
        } else 0.0

        val gpsAvailability = lastLocation != null
        val gpsAccuracy = lastLocation?.accuracy ?: 20f
        val vibrationConsistency = vibrationAnalyzer.calculateConsistency(bufferCopy)
        val speed = lastLocation?.speed ?: 0f
        val confidence = confidenceCalculator.calculateConfidence(
            gpsAvailability = gpsAvailability,
            gpsAccuracy = gpsAccuracy,
            vibrationConsistency = vibrationConsistency,
            speed = speed
        )

        val conditionAuto = classifyCondition(avgVibration)
        val finalCondition = manualCondition?.takeIf { it.isNotBlank() } ?: conditionAuto

        val segment = RoadSegment(
            sessionId = currentSessionId ?: return null,
            startDistance = segmentStartDistance,
            endDistance = endDist,
            avgVibration = avgVibration,
            conditionAuto = finalCondition,
            confidence = confidence,
            name = name,
            surfaceType = surfaceType,
            notes = notes,
            photoPath = photoPath,
            audioPath = audioPath,
            startLat = startLocation?.latitude ?: 0.0,
            startLng = startLocation?.longitude ?: 0.0,
            endLat = endLocation?.latitude ?: 0.0,
            endLng = endLocation?.longitude ?: 0.0,
            createdAt = System.currentTimeMillis()
        )

        surveyRepository.insertSegment(segment)
        resetSegment()
        return segment
    }

    fun cancelSegment() {
        resetSegment()
    }

    private fun resetSegment() {
        synchronized(segmentVibrationBuffer) {
            isSegmentStarted = false
            segmentVibrationBuffer.clear()
            segmentStartDistance = 0.0
            segmentStartLocation = null
            _isSegmentActive.value = false
        }
    }

    // ========== REALTIME ASSESSMENT ==========

    fun getCurrentAvgVibration(): Double {
        val bufferCopy = synchronized(segmentVibrationBuffer) { segmentVibrationBuffer.toList() }
        return if (bufferCopy.isNotEmpty()) {
            vibrationAnalyzer.analyzeVibration(bufferCopy)
        } else 0.0
    }

    fun getCurrentConditionAuto(): String = classifyCondition(getCurrentAvgVibration())

    private fun classifyCondition(avgVibration: Double): String = when {
        avgVibration < Constants.DEFAULT_THRESHOLD_BAIK -> Constants.CONDITION_BAIK
        avgVibration < Constants.DEFAULT_THRESHOLD_SEDANG -> Constants.CONDITION_SEDANG
        avgVibration < Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN -> Constants.CONDITION_RUSAK_RINGAN
        else -> Constants.CONDITION_RUSAK_BERAT
    }

    fun getCurrentConfidence(): Int {
        val bufferCopy = synchronized(segmentVibrationBuffer) { segmentVibrationBuffer.toList() }
        val gpsAvailability = lastLocation != null
        val gpsAccuracy = lastLocation?.accuracy ?: 20f
        val vibrationConsistency = vibrationAnalyzer.calculateConsistency(bufferCopy)
        val speed = lastLocation?.speed ?: 0f
        return confidenceCalculator.calculateConfidence(
            gpsAvailability = gpsAvailability,
            gpsAccuracy = gpsAccuracy,
            vibrationConsistency = vibrationConsistency,
            speed = speed
        )
    }

    fun getLastLocation(): Location? = lastLocation
}