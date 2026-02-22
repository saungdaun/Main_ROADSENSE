package zaujaani.roadsensebasic.domain.engine

import android.location.Location
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
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
import javax.inject.Named
import kotlin.math.*
import java.lang.Math.toRadians

@Singleton
class SurveyEngine @Inject constructor(
    private val sensorGateway: SensorGateway,
    private val telemetryRepository: TelemetryRepository,
    private val surveyRepository: SurveyRepository,
    private val vibrationAnalyzer: VibrationAnalyzer,
    private val confidenceCalculator: ConfidenceCalculator,
    private val sdiCalculator: SDICalculator,
    private val pciCalculator: PCICalculator,                   // ← BARU
    @param:Named("applicationScope") private val externalScope: CoroutineScope
) {
    private val mutex = Mutex()

    private companion object {
        const val SEGMENT_LENGTH          = 100.0   // SDI: 100m per segmen
        const val SEGMENT_LENGTH_PCI      = 50.0    // PCI: 50m per segmen (ASTM D6433)
        const val DEFAULT_LANE_WIDTH      = 3.7     // meter (lebar lajur default)
        const val MIN_DISTANCE_DELTA      = 1.0
        const val SPEED_FILTER_FACTOR     = 0.2
        const val SENSOR_SAMPLE_INTERVAL_MS = 300L
        const val TELEMETRY_FLUSH_INTERVAL_MS = 2000L
    }

    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var currentSessionId: Long? = null
    private var currentDistanceValue = 0.0
    private var lastLocation: LocationData? = null
    private val telemetryBuffer = ArrayDeque<TelemetryRaw>()
    private var lastSaveTime = System.currentTimeMillis()
    private var lastProcessTime = 0L

    private var currentCondition: Condition = Condition.BAIK
    private var currentSurface: Surface = Surface.ASPAL
    private var currentMode: SurveyMode = SurveyMode.GENERAL

    // SDI segment state
    private var currentSegmentIndex = -1
    private var currentSegmentId: Long? = null

    // PCI segment state ← BARU
    private var currentPciSegmentIndex = -1
    private var currentPciSegmentId: Long? = null
    var laneWidthM: Double = DEFAULT_LANE_WIDTH     // bisa diset dari dialog start survey

    // ── Public flows ──────────────────────────────────────────────────────

    private val _currentDistance = MutableStateFlow(0.0)
    val currentDistance: StateFlow<Double> = _currentDistance.asStateFlow()

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

    // ── Public getters ────────────────────────────────────────────────────

    @Suppress("unused") fun getCurrentSessionId(): Long? = currentSessionId
    @Suppress("unused") fun getCurrentDistance(): Double = currentDistanceValue
    @Suppress("unused") fun getLastLocation(): LocationData? = lastLocation
    @Suppress("unused") fun getCurrentCondition(): Condition = currentCondition
    @Suppress("unused") fun getCurrentSurface(): Surface = currentSurface
    @Suppress("unused") fun getCurrentMode(): SurveyMode = currentMode
    @Suppress("unused") fun isActive(): Boolean = _sessionState.value == SessionState.SURVEYING
    @Suppress("unused") fun isPaused(): Boolean = _sessionState.value == SessionState.PAUSED
    // ← BARU
    fun getCurrentPciSegmentId(): Long? = currentPciSegmentId
    fun getCurrentSta(): String = formatSta(currentDistanceValue.toInt())

    // ══════════════════════════════════════════════════════════════════════
    // SESSION LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    suspend fun startNewSession(
        surveyorName: String = "",
        roadName: String = "",
        mode: SurveyMode = SurveyMode.GENERAL,
        laneWidth: Double = DEFAULT_LANE_WIDTH      // ← BARU: untuk PCI sample area
    ): Long {
        _roadName.value = roadName
        _mode.value = mode
        currentMode = mode
        laneWidthM = laneWidth

        val firstLoc = lastLocation
        val session = SurveySession(
            startTime    = System.currentTimeMillis(),
            deviceModel  = android.os.Build.MODEL,
            surveyorName = surveyorName,
            roadName     = roadName,
            startLat     = firstLoc?.latitude ?: 0.0,
            startLng     = firstLoc?.longitude ?: 0.0,
            mode         = mode
        )
        val id = surveyRepository.insertSession(session)
        currentSessionId    = id
        currentDistanceValue = 0.0
        _currentDistance.value = 0.0
        _vibrationHistory.value = emptyList()
        lastLocation    = null
        currentCondition = Condition.BAIK
        currentSurface  = Surface.ASPAL
        currentSegmentIndex    = -1
        currentSegmentId       = null
        currentPciSegmentIndex = -1     // ← BARU
        currentPciSegmentId    = null   // ← BARU
        lastSaveTime    = System.currentTimeMillis()
        lastProcessTime = 0L
        mutex.withLock { telemetryBuffer.clear() }
        _sessionState.value = SessionState.SURVEYING
        Timber.d("Session started: $id mode=$mode laneWidth=$laneWidthM")
        return id
    }

    suspend fun endCurrentSession() {
        if (_sessionState.value != SessionState.SURVEYING &&
            _sessionState.value != SessionState.PAUSED) return

        flushTelemetrySync()
        val sessionId = currentSessionId ?: return
        val session   = surveyRepository.getSessionById(sessionId) ?: return
        val lastLoc   = lastLocation

        // Hitung skor sesuai mode
        val totalSdi = if (currentMode == SurveyMode.SDI) calculateSessionSDI() else 0
        val totalPci = if (currentMode == SurveyMode.PCI) calculateSessionPCI() else -1  // ← BARU

        // Rata-rata confidence dari road segments
        val segments = surveyRepository.getSegmentsForSessionOnce(sessionId)
        val avgConf  = if (segments.isNotEmpty()) {
            segments.map { it.confidence }.average().toInt()
        } else 0

        surveyRepository.updateSession(
            session.copy(
                endTime       = System.currentTimeMillis(),
                totalDistance = currentDistanceValue,
                endLat        = lastLoc?.latitude ?: session.startLat,
                endLng        = lastLoc?.longitude ?: session.startLng,
                avgConfidence = avgConf,
                avgSdi        = totalSdi,
                avgPci        = totalPci    // ← BARU
            )
        )
        _sessionState.value = SessionState.ENDED
        clearSessionData()
    }

    suspend fun calculateSessionSDI(): Int {
        val sessionId = currentSessionId ?: return 0
        val segments  = surveyRepository.getSegmentSdiForSessionOnce(sessionId)
        return sdiCalculator.calculateAverageSDI(segments.map { it.sdiScore })
    }

    // ← BARU
    suspend fun calculateSessionPCI(): Int {
        val sessionId = currentSessionId ?: return -1
        return surveyRepository.getAveragePci(sessionId)
    }

    fun discardCurrentSession() {
        externalScope.launch {
            _sessionState.value = SessionState.IDLE
            clearSessionData()
        }
    }

    private suspend fun clearSessionData() {
        currentSessionId       = null
        currentDistanceValue   = 0.0
        _currentDistance.value = 0.0
        lastLocation           = null
        mutex.withLock { telemetryBuffer.clear() }
        _vibrationHistory.value = emptyList()
        _currentVibration.value = 0f
        currentSegmentIndex    = -1
        currentSegmentId       = null
        currentPciSegmentIndex = -1     // ← BARU
        currentPciSegmentId    = null   // ← BARU
        Timber.d("Session data cleared")
    }

    fun pauseSurvey() {
        if (_sessionState.value == SessionState.SURVEYING)
            _sessionState.value = SessionState.PAUSED
    }

    fun resumeSurvey() {
        if (_sessionState.value == SessionState.PAUSED)
            _sessionState.value = SessionState.SURVEYING
    }

    // ══════════════════════════════════════════════════════════════════════
    // LOCATION & TELEMETRY
    // ══════════════════════════════════════════════════════════════════════

    fun updateLocation(locationData: LocationData) {
        if (_sessionState.value != SessionState.SURVEYING) return

        val now = System.currentTimeMillis()
        if (now - lastProcessTime < SENSOR_SAMPLE_INTERVAL_MS) return
        lastProcessTime = now

        lastLocation?.let { last ->
            val rawDelta = calculateDistance(last, locationData)
            val speed    = locationData.speed.coerceAtLeast(0.1f)
            val dynamicThreshold = MIN_DISTANCE_DELTA + (speed * SPEED_FILTER_FACTOR)

            if (rawDelta > dynamicThreshold && locationData.accuracy < Constants.GPS_ACCURACY_THRESHOLD) {
                val previousDistance = currentDistanceValue
                currentDistanceValue += rawDelta
                _currentDistance.value = currentDistanceValue

                // Trigger setiap 100m
                val previous100 = (previousDistance / SEGMENT_LENGTH).toInt()
                val current100  = (currentDistanceValue / SEGMENT_LENGTH).toInt()
                if (current100 > previous100) _distanceTrigger.tryEmit(currentDistanceValue)

                // ── SDI: buat segmen baru setiap 100m ────────────────────
                if (currentMode == SurveyMode.SDI) {
                    val newSegIdx = (currentDistanceValue / SEGMENT_LENGTH).toInt()
                    if (newSegIdx != currentSegmentIndex) {
                        currentSegmentIndex = newSegIdx
                        externalScope.launch {
                            val sessionId = currentSessionId ?: return@launch
                            val seg = SegmentSdi(
                                sessionId    = sessionId,
                                segmentIndex = newSegIdx,
                                startSta     = formatSta((newSegIdx * SEGMENT_LENGTH).toInt()),
                                endSta       = formatSta(((newSegIdx + 1) * SEGMENT_LENGTH).toInt()),
                                createdAt    = System.currentTimeMillis()
                            )
                            currentSegmentId = surveyRepository.insertSegmentSdi(seg)
                        }
                    }
                }

                // ── PCI: buat segmen baru setiap 50m ─────────────────────
                if (currentMode == SurveyMode.PCI) {
                    val newSegIdx = (currentDistanceValue / SEGMENT_LENGTH_PCI).toInt()
                    if (newSegIdx != currentPciSegmentIndex) {
                        currentPciSegmentIndex = newSegIdx
                        val loc = locationData
                        externalScope.launch {
                            val sessionId  = currentSessionId ?: return@launch
                            val sampleArea = SEGMENT_LENGTH_PCI * laneWidthM
                            val seg = SegmentPci(
                                sessionId      = sessionId,
                                segmentIndex   = newSegIdx,
                                startSta       = formatSta((newSegIdx * SEGMENT_LENGTH_PCI).toInt()),
                                endSta         = formatSta(((newSegIdx + 1) * SEGMENT_LENGTH_PCI).toInt()),
                                startLat       = loc.latitude,
                                startLng       = loc.longitude,
                                segmentLengthM = SEGMENT_LENGTH_PCI,
                                laneWidthM     = laneWidthM,
                                sampleAreaM2   = sampleArea,
                                createdAt      = System.currentTimeMillis(),
                                updatedAt      = System.currentTimeMillis()
                            )
                            currentPciSegmentId = surveyRepository.insertSegmentPci(seg)
                        }
                    }
                }
            }
        }
        lastLocation = locationData

        // ── Sensor & Telemetry ────────────────────────────────────────────
        val vibrationZ = sensorGateway.getLatestVibration()
        val vibrationX = sensorGateway.axisX.value
        val vibrationY = sensorGateway.axisY.value

        _currentVibration.value = vibrationZ
        _vibrationHistory.update { currentList ->
            (currentList + vibrationZ).takeLast(Constants.VIBRATION_HISTORY_MAX)
        }

        val sessionId = currentSessionId ?: return

        val vibrationConsistency = if (_vibrationHistory.value.isNotEmpty()) {
            vibrationAnalyzer.calculateConsistency(_vibrationHistory.value)
        } else 0.5

        val confidence = confidenceCalculator.calculateConfidence(
            gpsAvailability      = true,
            gpsAccuracy          = locationData.accuracy,
            vibrationConsistency = vibrationConsistency,
            speed                = locationData.speed
        )

        val telemetry = TelemetryRaw(
            sessionId          = sessionId,
            timestamp          = Instant.now(),
            latitude           = locationData.latitude,
            longitude          = locationData.longitude,
            altitude           = locationData.altitude,
            speed              = locationData.speed,
            vibrationX         = vibrationX,
            vibrationY         = vibrationY,
            vibrationZ         = vibrationZ,
            gpsAccuracy        = locationData.accuracy,
            cumulativeDistance = currentDistanceValue,
            condition          = currentCondition,
            surface            = currentSurface,
            confidence         = confidence
        )

        externalScope.launch {
            mutex.withLock { telemetryBuffer.addLast(telemetry) }
        }

        if (telemetryBuffer.size >= Constants.TELEMETRY_BUFFER_SIZE ||
            now - lastSaveTime > TELEMETRY_FLUSH_INTERVAL_MS) {
            flushTelemetry()
            lastSaveTime = now
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DISTRESS — SDI
    // ══════════════════════════════════════════════════════════════════════

    fun addDistressItem(
        type: DistressType,
        severity: Severity,
        lengthOrArea: Double,
        photoPath: String = "",
        audioPath: String = "",
        notes: String? = null
    ) {
        if (currentMode != SurveyMode.SDI) return
        val location  = lastLocation ?: return
        val segmentId = currentSegmentId ?: return
        val sessionId = currentSessionId ?: return

        val item = DistressItem(
            segmentId    = segmentId,
            sessionId    = sessionId,
            type         = type,
            severity     = severity,
            lengthOrArea = lengthOrArea,
            photoPath    = photoPath,
            audioPath    = audioPath,
            gpsLat       = location.latitude,
            gpsLng       = location.longitude,
            sta          = formatSta(currentDistanceValue.toInt()),
            createdAt    = System.currentTimeMillis()
        )
        externalScope.launch {
            try {
                surveyRepository.insertDistressItem(item)
                val items = surveyRepository.getDistressForSegmentOnce(segmentId)
                val sdi   = sdiCalculator.calculateSegmentSDI(items, segmentLength = SEGMENT_LENGTH)
                surveyRepository.updateSegmentSdiScore(segmentId, sdi, items.size)
            } catch (e: Exception) {
                Timber.e(e, "Failed to add SDI distress item")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DISTRESS — PCI  ← BARU
    // ══════════════════════════════════════════════════════════════════════

    fun addPciDistressItem(
        type: PCIDistressType,
        severity: Severity,
        quantity: Double,
        photoPath: String = "",
        audioPath: String = "",
        notes: String = ""
    ) {
        if (currentMode != SurveyMode.PCI) return
        val location  = lastLocation ?: return
        val segmentId = currentPciSegmentId ?: return
        val sessionId = currentSessionId ?: return

        externalScope.launch {
            try {
                val segment    = surveyRepository.getSegmentPciById(segmentId)
                val sampleArea = segment?.sampleAreaM2 ?: (SEGMENT_LENGTH_PCI * laneWidthM)
                val density    = (quantity / sampleArea) * 100.0

                // Hitung DV single item via calculator
                val dvResult = pciCalculator.calculate(
                    distresses   = listOf(PCICalculator.DistressInput(type, severity, quantity, sampleArea)),
                    sampleAreaM2 = sampleArea
                )
                val dv = dvResult.distressBreakdown.firstOrNull()?.deductValue ?: 0.0

                val item = PCIDistressItem(
                    segmentId   = segmentId,
                    sessionId   = sessionId,
                    type        = type,
                    severity    = severity,
                    quantity    = quantity,
                    notes       = notes,
                    density     = density,
                    deductValue = dv,
                    photoPath   = photoPath,
                    audioPath   = audioPath,
                    gpsLat      = location.latitude,
                    gpsLng      = location.longitude,
                    sta         = formatSta(currentDistanceValue.toInt()),
                    createdAt   = System.currentTimeMillis()
                )
                surveyRepository.insertPciDistressItem(item)

                // Recalculate PCI seluruh segmen setelah item baru masuk
                recalculateSegmentPCI(segmentId, sampleArea)

            } catch (e: Exception) {
                Timber.e(e, "Failed to add PCI distress item")
            }
        }
    }

    private suspend fun recalculateSegmentPCI(segmentId: Long, sampleArea: Double) {
        val items  = surveyRepository.getPciDistressForSegmentOnce(segmentId)
        val inputs = items.map {
            PCICalculator.DistressInput(it.type, it.severity, it.quantity, sampleArea)
        }
        val result   = pciCalculator.calculate(inputs, sampleArea)
        val dominant = result.distressBreakdown.maxByOrNull { it.deductValue }?.type?.displayName ?: ""

        surveyRepository.updateSegmentPciScore(
            segmentId     = segmentId,
            pciScore      = result.pciScore,
            pciRating     = result.rating.name,
            cdv           = result.correctedDeduct,
            distressCount = items.size,
            dominantType  = dominant,
            dvList        = result.deductValues
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // EVENTS
    // ══════════════════════════════════════════════════════════════════════

    fun recordConditionChange(condition: Condition) {
        currentCondition = condition
        recordEvent(EventType.CONDITION_CHANGE, condition.name)
    }

    fun recordSurfaceChange(surface: Surface) {
        currentSurface = surface
        recordEvent(EventType.SURFACE_CHANGE, surface.name)
    }

    fun recordPhoto(path: String, notes: String? = null) = recordEvent(EventType.PHOTO, path, notes)
    fun recordVoice(path: String, notes: String? = null) = recordEvent(EventType.VOICE, path, notes)

    private fun recordEvent(type: EventType, value: String, notes: String? = null) {
        val location  = lastLocation ?: return
        val sessionId = currentSessionId ?: return
        val event = RoadEvent(
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            latitude  = location.latitude,
            longitude = location.longitude,
            distance  = currentDistanceValue.toFloat(),
            eventType = type,
            value     = value,
            notes     = notes
        )
        externalScope.launch {
            try {
                surveyRepository.insertEvent(event)
            } catch (e: Exception) {
                Timber.e(e, "Failed to insert event")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // VALIDATION
    // ══════════════════════════════════════════════════════════════════════

    private val conditionRanges = mapOf(
        Condition.BAIK         to (0f..(Constants.DEFAULT_THRESHOLD_BAIK * 1.2f)),
        Condition.SEDANG       to ((Constants.DEFAULT_THRESHOLD_BAIK * 0.8f)..(Constants.DEFAULT_THRESHOLD_SEDANG * 1.2f)),
        Condition.RUSAK_RINGAN to ((Constants.DEFAULT_THRESHOLD_SEDANG * 0.8f)..(Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN * 1.2f)),
        Condition.RUSAK_BERAT  to ((Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN * 0.8f)..Float.MAX_VALUE)
    )

    fun checkConditionConsistency(selectedCondition: Condition): Boolean =
        conditionRanges[selectedCondition]?.contains(_currentVibration.value) == true

    // ══════════════════════════════════════════════════════════════════════
    // TELEMETRY FLUSH
    // ══════════════════════════════════════════════════════════════════════

    fun flushTelemetry() {
        externalScope.launch { flushTelemetryInternal(enableRetry = true) }
    }

    private suspend fun flushTelemetrySync() {
        flushTelemetryInternal(enableRetry = false)
    }

    private suspend fun flushTelemetryInternal(enableRetry: Boolean) {
        val snapshot = mutex.withLock {
            if (telemetryBuffer.isEmpty()) return@withLock emptyList<TelemetryRaw>()
            telemetryBuffer.toList().also { telemetryBuffer.clear() }
        }
        if (snapshot.isEmpty()) return

        if (!enableRetry) {
            try {
                telemetryRepository.insertAll(snapshot)
                Timber.d("Sync flushed ${snapshot.size} telemetries")
            } catch (e: Exception) {
                Timber.e(e, "Sync flush failed, restoring buffer")
                mutex.withLock { telemetryBuffer.addAll(snapshot) }
            }
            return
        }

        var retryCount = 0
        var backoff = 100L
        val maxRetries = 3
        while (retryCount < maxRetries) {
            try {
                telemetryRepository.insertAll(snapshot)
                Timber.d("Flushed ${snapshot.size} telemetries")
                return
            } catch (e: Exception) {
                retryCount++
                if (retryCount >= maxRetries) {
                    Timber.e(e, "Failed to flush telemetry after $maxRetries attempts")
                    mutex.withLock { telemetryBuffer.addAll(snapshot) }
                } else {
                    Timber.w(e, "Flush failed (attempt $retryCount), retrying in ${backoff}ms")
                    delay(backoff)
                    backoff *= 2
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private fun calculateDistance(from: LocationData, to: LocationData): Double {
        val r    = 6371000.0
        val lat1 = toRadians(from.latitude)
        val lat2 = toRadians(to.latitude)
        val dLat = toRadians(to.latitude - from.latitude)
        val dLon = toRadians(to.longitude - from.longitude)
        val a    = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun formatSta(meters: Int): String {
        val km = meters / 1000
        val m  = meters % 1000
        return String.format(Locale.US, "%d+%03d", km, m)
    }

    @Suppress("unused")
    fun destroyEngine() {
        val currentState = _sessionState.value
        if (currentState == SessionState.SURVEYING || currentState == SessionState.PAUSED) {
            Timber.w("destroyEngine called while session is $currentState. Session will be discarded.")
        }
        externalScope.coroutineContext.cancelChildren()
        externalScope.launch {
            _sessionState.value = SessionState.IDLE
            clearSessionData()
        }
        Timber.d("SurveyEngine destroyed")
    }
}

enum class SessionState {
    IDLE,
    SURVEYING,
    PAUSED,
    ENDED
}