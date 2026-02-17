// ==================== SurveyEngine.kt (perbaikan) ====================
package zaujaani.roadsensebasic.domain.engine

import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zaujaani.roadsensebasic.data.local.entity.RoadSegment
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.local.entity.TelemetryRaw
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.data.repository.TelemetryRepository
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.util.Constants
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurveyEngine @Inject constructor(
    private val sensorGateway: SensorGateway,
    private val telemetryRepository: TelemetryRepository,
    private val surveyRepository: SurveyRepository,
    private val vibrationAnalyzer: VibrationAnalyzer,
    private val confidenceCalculator: ConfidenceCalculator
) {
    private var currentSessionId: Long? = null
    private var isActive = false
    private var currentDistanceValue = 0.0
    private var lastLocation: Location? = null
    private val telemetryBuffer = mutableListOf<TelemetryRaw>()
    private var lastSaveTime = System.currentTimeMillis()

    private val _currentDistance = MutableStateFlow(0.0)
    val currentDistance: StateFlow<Double> = _currentDistance.asStateFlow()

    private var segmentStartDistance = 0.0
    private val segmentVibrationBuffer = mutableListOf<Float>()
    private var segmentStartTime = 0L
    private var isSegmentStarted = false

    fun isSurveying(): Boolean = isActive
    fun getCurrentSessionId(): Long? = currentSessionId
    fun getCurrentDistance(): Double = currentDistanceValue
    fun getSegmentStartDistance(): Double = segmentStartDistance
    fun isSegmentActive(): Boolean = isSegmentStarted

    suspend fun startNewSession(): Long {
        val session = SurveySession(
            startTime = System.currentTimeMillis(),
            deviceModel = android.os.Build.MODEL
        )
        val id = surveyRepository.createSession(session)
        currentSessionId = id
        isActive = true
        currentDistanceValue = 0.0
        _currentDistance.value = 0.0
        lastLocation = null
        telemetryBuffer.clear()
        resetSegment()
        return id
    }

    suspend fun endCurrentSession() {
        if (!isActive) return
        flushTelemetry()
        val session = surveyRepository.getSessionById(currentSessionId ?: return)
        if (session != null) {
            val updatedSession = session.copy(
                endTime = System.currentTimeMillis(),
                totalDistance = currentDistanceValue
            )
            surveyRepository.updateSession(updatedSession)
        }
        isActive = false
        currentSessionId = null
    }

    fun discardCurrentSession() {
        isActive = false
        currentSessionId = null
        currentDistanceValue = 0.0
        _currentDistance.value = 0.0
        lastLocation = null
        telemetryBuffer.clear()
        resetSegment()
    }

    fun updateLocation(location: Location) {
        if (!isActive) return
        lastLocation?.let { last ->
            val results = FloatArray(1)
            Location.distanceBetween(
                last.latitude, last.longitude,
                location.latitude, location.longitude,
                results
            )
            currentDistanceValue += results[0]
            _currentDistance.value = currentDistanceValue
        }
        lastLocation = location

        val vibration = sensorGateway.getLatestVibration()

        val telemetry = TelemetryRaw(
            sessionId = currentSessionId ?: return,
            timestamp = System.currentTimeMillis(),
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,  // ✅ TAMBAHKAN INI
            speed = location.speed,
            vibrationZ = vibration,
            gpsAccuracy = location.accuracy,
            cumulativeDistance = currentDistanceValue
        )
        telemetryBuffer.add(telemetry)

        val now = System.currentTimeMillis()
        if (telemetryBuffer.size >= 10 || now - lastSaveTime > 2000) {
            flushTelemetry()
            lastSaveTime = now
        }

        if (isSegmentStarted) {
            segmentVibrationBuffer.add(vibration)
        }
    }

    fun flushTelemetry() {
        if (telemetryBuffer.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            telemetryRepository.insertAll(telemetryBuffer.toList())
            telemetryBuffer.clear()
        }
    }

    fun startSegment() {
        if (!isActive) return
        segmentStartDistance = currentDistanceValue
        segmentVibrationBuffer.clear()
        segmentStartTime = System.currentTimeMillis()
        isSegmentStarted = true
    }

    suspend fun endSegment(
        name: String,
        manualCondition: String?,
        surfaceType: String,
        notes: String,
        photoPath: String
    ): RoadSegment? {
        if (!isSegmentStarted) return null
        val endDist = currentDistanceValue
        val avgVibration = if (segmentVibrationBuffer.isNotEmpty()) {
            vibrationAnalyzer.analyzeVibration(segmentVibrationBuffer)
        } else 0.0

        val gpsAvailability = lastLocation != null
        val gpsAccuracy = lastLocation?.accuracy ?: 20f
        val vibrationConsistency = vibrationAnalyzer.calculateConsistency(segmentVibrationBuffer)
        val speed = lastLocation?.speed ?: 0f
        val confidence = confidenceCalculator.calculateConfidence(
            gpsAvailability = gpsAvailability,
            gpsAccuracy = gpsAccuracy,
            vibrationConsistency = vibrationConsistency,
            speed = speed
        )

        val conditionAuto = when {
            avgVibration < Constants.DEFAULT_THRESHOLD_BAIK -> Constants.CONDITION_BAIK
            avgVibration < Constants.DEFAULT_THRESHOLD_SEDANG -> Constants.CONDITION_SEDANG
            avgVibration < Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN -> Constants.CONDITION_RUSAK_RINGAN
            else -> Constants.CONDITION_RUSAK_BERAT
        }
        val finalCondition = manualCondition ?: conditionAuto

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
            audioPath = "",
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
        isSegmentStarted = false
        segmentVibrationBuffer.clear()
        segmentStartDistance = 0.0
    }

    fun getCurrentAvgVibration(): Double {
        return if (segmentVibrationBuffer.isNotEmpty()) {
            vibrationAnalyzer.analyzeVibration(segmentVibrationBuffer)
        } else 0.0
    }

    fun getCurrentConditionAuto(): String {
        val avg = getCurrentAvgVibration()
        return when {
            avg < Constants.DEFAULT_THRESHOLD_BAIK -> Constants.CONDITION_BAIK
            avg < Constants.DEFAULT_THRESHOLD_SEDANG -> Constants.CONDITION_SEDANG
            avg < Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN -> Constants.CONDITION_RUSAK_RINGAN
            else -> Constants.CONDITION_RUSAK_BERAT
        }
    }

    fun getCurrentConfidence(): Int {
        val gpsAvailability = lastLocation != null
        val gpsAccuracy = lastLocation?.accuracy ?: 20f
        val vibrationConsistency = vibrationAnalyzer.calculateConsistency(segmentVibrationBuffer)
        val speed = lastLocation?.speed ?: 0f
        return confidenceCalculator.calculateConfidence(
            gpsAvailability = gpsAvailability,
            gpsAccuracy = gpsAccuracy,
            vibrationConsistency = vibrationConsistency,
            speed = speed
        )
    }
}