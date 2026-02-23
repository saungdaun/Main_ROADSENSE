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
import zaujaani.roadsensebasic.util.sensor.GpsConfidenceFilter

@Singleton
class SurveyEngine @Inject constructor(
    private val sensorGateway: SensorGateway,
    private val telemetryRepository: TelemetryRepository,
    private val surveyRepository: SurveyRepository,
    private val vibrationAnalyzer: VibrationAnalyzer,
    private val confidenceCalculator: ConfidenceCalculator,
    private val sdiCalculator: SDICalculator,
    private val pciCalculator: PCICalculator,
    gpsConfidenceFilter: GpsConfidenceFilter,
    @param:Named("applicationScope") private val externalScope: CoroutineScope
) {
    // Dedicated scope for all internal coroutines – prevents interference with app scope
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    private companion object {
        const val SEGMENT_LENGTH          = 100.0   // SDI: 100m per segmen
        const val SEGMENT_LENGTH_PCI      = 50.0    // PCI: 50m per segmen (ASTM D6433)
        const val DEFAULT_LANE_WIDTH      = 3.7     // meter (lebar lajur default)
        // FIX #ENG-1: MIN_DISTANCE_DELTA 1.0→0.5 — tidak reject titik valid saat pelan
        // FIX #ENG-2: SPEED_FILTER_FACTOR 0.2→0.05 — kurangi agresivitas saat kecepatan tinggi
        //   Sebelumnya: 60 km/h → threshold 4.3m. Sekarang: 60 km/h → threshold 1.3m
        // FIX #ENG-3: SENSOR_SAMPLE_INTERVAL_MS 300→150ms (GPS sekarang 500ms)
        const val MIN_DISTANCE_DELTA        = 0.5
        const val SPEED_FILTER_FACTOR       = 0.05
        const val SENSOR_SAMPLE_INTERVAL_MS = 150L
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

    // PCI segment state
    private var currentPciSegmentIndex = -1
    private var currentPciSegmentId: Long? = null
    var laneWidthM: Double = DEFAULT_LANE_WIDTH

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
    fun getCurrentPciSegmentId(): Long? = currentPciSegmentId
    fun getCurrentSta(): String = formatSta(currentDistanceValue.toInt())

    // ══════════════════════════════════════════════════════════════════════
    // SESSION LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    suspend fun startNewSession(
        surveyorName: String = "",
        roadName: String = "",
        mode: SurveyMode = SurveyMode.GENERAL,
        laneWidth: Double = DEFAULT_LANE_WIDTH
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
        currentPciSegmentIndex = -1
        currentPciSegmentId    = null
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

        val totalSdi = if (currentMode == SurveyMode.SDI) calculateSessionSDI() else 0
        val totalPci = if (currentMode == SurveyMode.PCI) calculateSessionPCI() else -1

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
                avgPci        = totalPci
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

    suspend fun calculateSessionPCI(): Int {
        val sessionId = currentSessionId ?: return -1
        return surveyRepository.getAveragePci(sessionId)
    }

    fun discardCurrentSession() {
        engineScope.launch {
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
        currentPciSegmentIndex = -1
        currentPciSegmentId    = null
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

        // FIX #ENG-4 (BUG KRITIS): `lastLocation` sebelumnya selalu diupdate di akhir fungsi,
        // bahkan saat titik GAGAL lolos filter dinamis.
        // Akibat: titik berikutnya menghitung delta dari titik YANG DITOLAK,
        // bukan dari titik terakhir yang valid → jarak under-count + garis macet.
        //
        // Perbaikan: lastLocation HANYA diupdate jika titik berhasil lolos filter.
        // Jika ditolak, lastLocation tetap pada titik valid terakhir.

        lastLocation?.let { last ->
            val rawDelta = calculateDistance(last, locationData)
            val dynamicThreshold = MIN_DISTANCE_DELTA + (locationData.speed.coerceAtLeast(0f) * SPEED_FILTER_FACTOR)

            // FIX #ENG-5: Hapus cek akurasi di sini (sudah difilter di SurveyForegroundService).
            // Double-filtering menyebabkan lebih banyak titik dibuang dari yang perlu.
            if (rawDelta > dynamicThreshold) {
                val previousDistance = currentDistanceValue
                currentDistanceValue += rawDelta
                _currentDistance.value = currentDistanceValue

                val previous100 = (previousDistance / SEGMENT_LENGTH).toInt()
                val current100  = (currentDistanceValue / SEGMENT_LENGTH).toInt()
                if (current100 > previous100) _distanceTrigger.tryEmit(currentDistanceValue)

                if (currentMode == SurveyMode.SDI) {
                    val newSegIdx = (currentDistanceValue / SEGMENT_LENGTH).toInt()
                    if (newSegIdx != currentSegmentIndex) {
                        currentSegmentIndex = newSegIdx
                        engineScope.launch {
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

                if (currentMode == SurveyMode.PCI) {
                    val newSegIdx = (currentDistanceValue / SEGMENT_LENGTH_PCI).toInt()
                    if (newSegIdx != currentPciSegmentIndex) {
                        currentPciSegmentIndex = newSegIdx
                        val loc = locationData
                        engineScope.launch {
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

                // FIX #ENG-4: Update lastLocation HANYA jika titik berhasil diproses
                lastLocation = locationData

            } else {
                // Titik terlalu dekat / di bawah threshold — jangan update lastLocation.
                // Dengan begitu, titik berikutnya akan menghitung delta dari titik VALID terakhir,
                // bukan dari titik yang ditolak.
                Timber.v("GPS point skipped: delta=%.2fm < threshold=%.2fm".format(rawDelta, dynamicThreshold))
            }
        } ?: run {
            // Titik pertama (lastLocation masih null) → langsung set tanpa filter
            lastLocation = locationData
        }

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

        // ════════════════════════════════════════════════════════════════════
        // TELEMETRY BUFFER & FLUSH — VERSI SOLID (lastSaveTime dalam mutex)
        // ════════════════════════════════════════════════════════════════════
        engineScope.launch {
            val now = System.currentTimeMillis()

            val shouldFlush = mutex.withLock {
                telemetryBuffer.addLast(telemetry)

                val needFlush =
                    telemetryBuffer.size >= Constants.TELEMETRY_BUFFER_SIZE ||
                            now - lastSaveTime > TELEMETRY_FLUSH_INTERVAL_MS

                if (needFlush) {
                    lastSaveTime = now
                }

                needFlush
            }

            if (shouldFlush) {
                flushTelemetry()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DISTRESS — SDI
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Simpan item kerusakan SDI ke database.
     *
     * FIX KRITIS — silent drop sebelumnya:
     * - `lastLocation == null`  → surveyor belum bergerak setelah start session
     * - `currentSegmentId == null` → belum jalan 100m, segment belum dibuat otomatis
     * Keduanya menyebabkan data + foto tidak tersimpan sama sekali tanpa notifikasi error.
     *
     * Perbaikan:
     * 1. `lastLocation == null` → gunakan LocationData(0,0) sebagai fallback
     * 2. `currentSegmentId == null` → auto-create segmen 0 (STA 0+000–0+100)
     * 3. Fungsi kini `suspend` + return `Boolean` agar ViewModel bisa cek keberhasilan
     */
    suspend fun addDistressItem(
        type: DistressType,
        severity: Severity,
        lengthOrArea: Double,
        photoPath: String = "",
        audioPath: String = "",
        notes: String? = null
    ): Boolean {
        if (currentMode != SurveyMode.SDI) return false
        val sessionId = currentSessionId ?: return false

        // FIX: Jangan tolak karena belum ada GPS fix — gunakan fallback 0,0
        val location = lastLocation ?: LocationData(
            latitude = 0.0, longitude = 0.0, altitude = 0.0,
            speed = 0f, accuracy = 999f
        )

        // FIX: Auto-create segmen 0 jika belum ada (surveyor belum bergerak 100m)
        val segmentId: Long = currentSegmentId ?: run {
            val seg = SegmentSdi(
                sessionId    = sessionId,
                segmentIndex = 0,
                startSta     = formatSta(0),
                endSta       = formatSta(SEGMENT_LENGTH.toInt()),
                createdAt    = System.currentTimeMillis()
            )
            val newId = surveyRepository.insertSegmentSdi(seg)
            currentSegmentIndex = 0
            currentSegmentId    = newId
            Timber.d("Auto-created SDI segment 0 for sessionId=$sessionId")
            newId
        }

        return try {
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
                notes        = notes ?: "",
                createdAt    = System.currentTimeMillis()
            )
            surveyRepository.insertDistressItem(item)
            val items = surveyRepository.getDistressForSegmentOnce(segmentId)
            sdiCalculator.calculateSegmentSDI(items, segmentLength = SEGMENT_LENGTH)
            Timber.d("DistressItem saved: type=${type.name} seg=$segmentId photo=${photoPath.isNotBlank()}")
            true
        } catch (e: Exception) {
            Timber.e(e, "addDistressItem error")
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DISTRESS — PCI
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Simpan item kerusakan PCI ke database.
     *
     * FIX KRITIS — silent drop sebelumnya:
     * - `lastLocation == null`      → belum ada GPS fix setelah start session
     * - `currentPciSegmentId == null` → belum jalan 50m, segment belum dibuat otomatis
     *
     * Perbaikan:
     * 1. `lastLocation == null` → LocationData(0,0) sebagai fallback
     * 2. `currentPciSegmentId == null` → auto-create segmen PCI 0 (STA 0+000–0+050)
     * 3. Return `Boolean` agar ViewModel bisa propagate error ke UI
     */
    suspend fun addPciDistressItem(
        type: PCIDistressType,
        severity: Severity,
        quantity: Double,
        photoPath: String = "",
        audioPath: String = "",
        notes: String = ""
    ): Boolean {
        if (currentMode != SurveyMode.PCI) return false
        val sessionId = currentSessionId ?: return false

        // FIX: Fallback location saat GPS belum ada fix
        val location = lastLocation ?: LocationData(
            latitude = 0.0, longitude = 0.0, altitude = 0.0,
            speed = 0f, accuracy = 999f
        )

        // FIX: Auto-create segmen PCI 0 jika belum ada
        val segmentId: Long = currentPciSegmentId ?: run {
            val sampleArea = SEGMENT_LENGTH_PCI * laneWidthM
            val seg = SegmentPci(
                sessionId      = sessionId,
                segmentIndex   = 0,
                startSta       = formatSta(0),
                endSta         = formatSta(SEGMENT_LENGTH_PCI.toInt()),
                startLat       = location.latitude,
                startLng       = location.longitude,
                segmentLengthM = SEGMENT_LENGTH_PCI,
                laneWidthM     = laneWidthM,
                sampleAreaM2   = sampleArea,
                createdAt      = System.currentTimeMillis(),
                updatedAt      = System.currentTimeMillis()
            )
            val newId = surveyRepository.insertSegmentPci(seg)
            currentPciSegmentIndex = 0
            currentPciSegmentId    = newId
            Timber.d("Auto-created PCI segment 0 for sessionId=$sessionId sampleArea=$sampleArea")
            newId
        }

        return try {
            val segment    = surveyRepository.getSegmentPciById(segmentId)
            val sampleArea = segment?.sampleAreaM2 ?: (SEGMENT_LENGTH_PCI * laneWidthM)
            val density    = (quantity / sampleArea) * 100.0

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
            recalculateSegmentPCI(segmentId, sampleArea)
            Timber.d("PCIDistressItem saved: type=${type.name} seg=$segmentId photo=${photoPath.isNotBlank()}")
            true
        } catch (e: Exception) {
            Timber.e(e, "addPciDistressItem error")
            false
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
        val sessionId = currentSessionId ?: return

        // FIX: Jangan drop event karena GPS belum fix — gunakan 0,0 sebagai fallback.
        // Foto dari FAB kamera di awal survey sebelum bergerak tetap harus tersimpan.
        val location = lastLocation ?: LocationData(
            latitude = 0.0, longitude = 0.0, altitude = 0.0,
            speed = 0f, accuracy = 999f
        )

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
        engineScope.launch {
            try {
                surveyRepository.insertEvent(event)
            } catch (e: Exception) {
                Timber.e(e, "Failed to insert event: $type")
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
        engineScope.launch { flushTelemetryInternal(enableRetry = true) }
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

        // Set state idle dan bersihkan data secara sinkron sebelum scope dicancel
        _sessionState.value = SessionState.IDLE
        runBlocking {
            clearSessionData()
        }

        // Baru cancel scope agar tidak ada job baru
        engineScope.cancel()

        Timber.d("SurveyEngine destroyed")
    }
}

enum class SessionState {
    IDLE,
    SURVEYING,
    PAUSED,
    ENDED
}