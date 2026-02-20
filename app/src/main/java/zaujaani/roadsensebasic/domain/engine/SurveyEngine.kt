package zaujaani.roadsensebasic.domain.engine

import android.location.Location
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import zaujaani.roadsensebasic.data.local.entity.*
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.data.repository.TelemetryRepository
import zaujaani.roadsensebasic.domain.model.LocationData
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.util.Constants
import java.time.Instant
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurveyEngine @Inject constructor(
    private val sensorGateway: SensorGateway,
    private val telemetryRepository: TelemetryRepository,
    private val surveyRepository: SurveyRepository,
    private val vibrationAnalyzer: VibrationAnalyzer,
    private val confidenceCalculator: ConfidenceCalculator,
    private val sdiCalculator: SDICalculator
) {
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    private companion object {
        const val SEGMENT_LENGTH = 100.0
    }

    private var currentSessionId: Long? = null
    private var isActive = false
    private var currentDistanceValue = 0.0
    private var lastLocation: LocationData? = null
    private val telemetryBuffer = mutableListOf<TelemetryRaw>()
    private var lastSaveTime = System.currentTimeMillis()

    private var currentCondition: Condition = Condition.BAIK
    private var currentSurface: Surface = Surface.ASPAL
    private var currentMode: SurveyMode = SurveyMode.GENERAL

    private var currentSegmentIndex = -1
    private var currentSegmentId: Long? = null

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

    private val _mode = MutableStateFlow(SurveyMode.GENERAL)
    val mode: StateFlow<SurveyMode> = _mode.asStateFlow()

    fun getCurrentSessionId(): Long? = currentSessionId
    fun getCurrentDistance(): Double = currentDistanceValue
    fun getLastLocation(): LocationData? = lastLocation
    fun getCurrentCondition(): Condition = currentCondition
    fun getCurrentSurface(): Surface = currentSurface
    fun getCurrentMode(): SurveyMode = currentMode

    suspend fun startNewSession(surveyorName: String = "", roadName: String = "", mode: SurveyMode = SurveyMode.GENERAL): Long {
        _roadName.value = roadName
        _mode.value = mode
        currentMode = mode
        val firstLoc = lastLocation
        val session = SurveySession(
            startTime = System.currentTimeMillis(),
            deviceModel = android.os.Build.MODEL,
            surveyorName = surveyorName,
            roadName = roadName,
            startLat = firstLoc?.latitude ?: 0.0,
            startLng = firstLoc?.longitude ?: 0.0,
            mode = mode
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
        currentSegmentIndex = -1
        currentSegmentId = null
        return id
    }

    suspend fun endCurrentSession() {
        if (!isActive) return
        flushTelemetrySync()
        val sessionId = currentSessionId ?: return
        val session = surveyRepository.getSessionById(sessionId)
        if (session != null) {
            val lastLoc = lastLocation
            val totalSdi = if (currentMode == SurveyMode.SDI) calculateSessionSDI() else 0
            surveyRepository.updateSession(
                session.copy(
                    endTime = System.currentTimeMillis(),
                    totalDistance = currentDistanceValue,
                    endLat = lastLoc?.latitude ?: session.startLat,
                    endLng = lastLoc?.longitude ?: session.startLng,
                    avgConfidence = 0,
                    avgSdi = totalSdi
                )
            )
        }
        resetState()
    }

    suspend fun calculateSessionSDI(): Int {
        val sessionId = currentSessionId ?: return 0
        val segments = surveyRepository.getSegmentsForSessionOnce(sessionId)
        val scores = segments.map { it.sdiScore }
        return sdiCalculator.calculateAverageSDI(scores)
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
        currentSegmentIndex = -1
        currentSegmentId = null
    }

    fun pauseSurvey() {
        if (!isActive) return
        _isPaused.value = true
    }

    fun resumeSurvey() {
        if (!isActive) return
        _isPaused.value = false
    }

    fun updateLocation(locationData: LocationData) {
        if (!isActive || _isPaused.value) return

        lastLocation?.let { last ->
            val delta = calculateDistance(last, locationData)
            if (delta > 0.5 && locationData.accuracy < Constants.GPS_ACCURACY_THRESHOLD) {
                val previousDistance = currentDistanceValue
                currentDistanceValue += delta
                _currentDistance.value = currentDistanceValue

                val previous100 = (previousDistance / SEGMENT_LENGTH).toInt()
                val current100 = (currentDistanceValue / SEGMENT_LENGTH).toInt()
                if (current100 > previous100) {
                    engineScope.launch { _distanceTrigger.emit(currentDistanceValue) }
                }

                if (currentMode == SurveyMode.SDI) {
                    val newSegmentIndex = (currentDistanceValue / SEGMENT_LENGTH).toInt()
                    if (newSegmentIndex != currentSegmentIndex) {
                        currentSegmentIndex = newSegmentIndex
                        engineScope.launch {
                            val sessionId = currentSessionId ?: return@launch
                            val segment = SegmentSdi(
                                sessionId = sessionId,
                                segmentIndex = newSegmentIndex,
                                startSta = formatSta((newSegmentIndex * SEGMENT_LENGTH).toInt()),
                                endSta = formatSta(((newSegmentIndex + 1) * SEGMENT_LENGTH).toInt()),
                                createdAt = System.currentTimeMillis()
                            )
                            currentSegmentId = surveyRepository.insertSegmentSdi(segment)
                        }
                    }
                }
            }
        }
        lastLocation = locationData

        val vibrationZ = sensorGateway.getLatestVibration()
        val vibrationX = sensorGateway.axisX.value
        val vibrationY = sensorGateway.axisY.value

        _currentVibration.value = vibrationZ

        val updatedHistory = _vibrationHistory.value.toMutableList().also {
            it.add(vibrationZ)
            if (it.size > Constants.VIBRATION_HISTORY_MAX) it.removeAt(0)
        }
        _vibrationHistory.value = updatedHistory

        val sessionId = currentSessionId ?: return
        val telemetry = TelemetryRaw(
            sessionId = sessionId,
            timestamp = Instant.now(),
            latitude = locationData.latitude,
            longitude = locationData.longitude,
            altitude = locationData.altitude,
            speed = locationData.speed,
            vibrationX = vibrationX,
            vibrationY = vibrationY,
            vibrationZ = vibrationZ,
            gpsAccuracy = locationData.accuracy,
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

    private fun calculateDistance(from: LocationData, to: LocationData): Double {
        val R = 6371000.0
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    fun flushTelemetry() {
        engineScope.launch {
            val snapshot = mutex.withLock {
                if (telemetryBuffer.isEmpty()) return@launch
                telemetryBuffer.toList().also { telemetryBuffer.clear() }
            }

            try {
                telemetryRepository.insertAll(snapshot)
            } catch (e: Exception) {
                mutex.withLock {
                    telemetryBuffer.addAll(0, snapshot)
                }
            }
        }
    }

    private suspend fun flushTelemetrySync() {
        val snapshot = mutex.withLock {
            if (telemetryBuffer.isEmpty()) return
            telemetryBuffer.toList().also { telemetryBuffer.clear() }
        }
        telemetryRepository.insertAll(snapshot)
    }

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

    fun addDistressItem(
        type: DistressType,
        severity: Severity,
        lengthOrArea: Double,
        photoPath: String = "",
        audioPath: String = "",
        notes: String? = null
    ) {
        if (currentMode != SurveyMode.SDI) return
        val location = lastLocation ?: return
        val segmentId = currentSegmentId ?: return
        val sessionId = currentSessionId ?: return
        val sta = formatSta(currentDistanceValue.toInt())

        val item = DistressItem(
            segmentId = segmentId,
            sessionId = sessionId,
            type = type,
            severity = severity,
            lengthOrArea = lengthOrArea,
            photoPath = photoPath,
            audioPath = audioPath,
            gpsLat = location.latitude,
            gpsLng = location.longitude,
            sta = sta,
            createdAt = System.currentTimeMillis()
        )
        engineScope.launch {
            surveyRepository.insertDistressItem(item)
            val items = surveyRepository.getDistressForSegmentOnce(segmentId)
            val sdi = sdiCalculator.calculateSegmentSDI(items, segmentLength = SEGMENT_LENGTH)
            surveyRepository.updateSegmentSdiScore(segmentId, sdi, items.size)
        }
    }

    fun checkConditionConsistency(selectedCondition: Condition): Boolean {
        val vib = _currentVibration.value
        return when (selectedCondition) {
            Condition.BAIK -> vib < Constants.DEFAULT_THRESHOLD_BAIK * 1.2f
            Condition.SEDANG -> vib in (Constants.DEFAULT_THRESHOLD_BAIK * 0.8f)..(Constants.DEFAULT_THRESHOLD_SEDANG * 1.2f)
            Condition.RUSAK_RINGAN -> vib in (Constants.DEFAULT_THRESHOLD_SEDANG * 0.8f)..(Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN * 1.2f)
            Condition.RUSAK_BERAT -> vib > Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN * 0.8f
        }
    }

    private fun formatSta(meters: Int): String {
        val km = meters / 1000
        val m = meters % 1000
        return String.format("%d+%03d", km, m)
    }
}