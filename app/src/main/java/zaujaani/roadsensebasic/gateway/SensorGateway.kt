package zaujaani.roadsensebasic.gateway

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import zaujaani.roadsensebasic.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * SensorGateway v2 — Accelerometer Manager dengan dukungan External Sensor (ESP32)
 *
 * Perubahan utama:
 * - setExternalSensorActive(true): pause internal sensor, gunakan data ESP32
 * - setExternalSensorActive(false): resume internal sensor (auto-fallback)
 * - feedExternalVibration(): BluetoothGateway memanggil ini untuk feed data ESP32
 * - vibrationFlow tetap sama untuk SurveyEngine — tidak perlu modifikasi engine
 */
@Singleton
class SensorGateway @Inject constructor(
    private val context: Context
) {
    // Config
    private var alpha: Float = 0.15f
    private var rmsWindowSize: Int = 20

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Low-pass filter state
    private val gravity = FloatArray(3) { 0f }

    // RMS rolling
    private var rmsSumSquares = 0.0
    private val rmsBuffer = ArrayDeque<Float>(rmsWindowSize)

    // Flows — tetap sama, diisi dari internal ATAU external
    private val _vibrationRaw = MutableStateFlow(0f)
    val vibrationRaw: StateFlow<Float> = _vibrationRaw.asStateFlow()

    private val _vibrationFlow = MutableStateFlow(0f)
    val vibrationFlow: StateFlow<Float> = _vibrationFlow.asStateFlow()

    private val _axisX = MutableStateFlow(0f)
    val axisX: StateFlow<Float> = _axisX.asStateFlow()

    private val _axisY = MutableStateFlow(0f)
    val axisY: StateFlow<Float> = _axisY.asStateFlow()

    private val _magnitude = MutableStateFlow(0f)
    val magnitude: StateFlow<Float> = _magnitude.asStateFlow()

    // ── External sensor state ──────────────────────────────────────────────
    private val _isExternalActive = MutableStateFlow(false)
    val isExternalActive: StateFlow<Boolean> = _isExternalActive.asStateFlow()

    private val _externalBattery = MutableStateFlow(0f)
    val externalBattery: StateFlow<Float> = _externalBattery.asStateFlow()

    private val _externalTemperature = MutableStateFlow(0f)
    val externalTemperature: StateFlow<Float> = _externalTemperature.asStateFlow()

    val isAccelerometerAvailable: Boolean get() = accelerometer != null
    private var isListening = false

    // ── Configuration ─────────────────────────────────────────────────────

    fun setAlpha(alpha: Float) {
        require(alpha in 0f..1f)
        this.alpha = alpha
    }

    fun setRmsWindowSize(size: Int) {
        require(size > 0)
        rmsWindowSize = size
    }

    // ── External Sensor API ───────────────────────────────────────────────

    /**
     * Dipanggil oleh BluetoothGateway saat ESP32 connect/disconnect.
     * true  → matikan sensor internal, gunakan ESP32
     * false → nyalakan kembali sensor internal
     */
    fun setExternalSensorActive(active: Boolean) {
        _isExternalActive.value = active
        if (active) {
            // Pause internal sensor untuk hemat baterai
            if (isListening) {
                sensorManager.unregisterListener(sensorEventListener)
                isListening = false
                Timber.i("SensorGateway: Internal sensor PAUSED (ESP32 active)")
            }
            resetBuffers()
        } else {
            // Resume internal sensor
            if (!isListening && accelerometer != null) {
                sensorManager.registerListener(
                    sensorEventListener,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_GAME
                )
                isListening = true
                Timber.i("SensorGateway: Internal sensor RESUMED (ESP32 disconnected)")
            }
        }
    }

    /**
     * Feed data vibration dari ESP32.
     * Dipanggil oleh BluetoothGateway setiap kali data RS2 diterima.
     * Data dimasukkan ke RMS pipeline yang sama → SurveyEngine tidak perlu ubah.
     */
    fun feedExternalVibration(accelZ: Float) {
        if (!_isExternalActive.value) return // Ignore jika tidak dalam mode external
        processVibrationZ(accelZ)
    }

    fun feedExternalAux(batteryVoltage: Float, temperature: Float) {
        _externalBattery.value = batteryVoltage
        _externalTemperature.value = temperature
    }

    // ── Internal sensor listener ──────────────────────────────────────────

    @Synchronized
    fun startListening() {
        if (_isExternalActive.value) {
            Timber.d("SensorGateway: skip startListening — external sensor active")
            return
        }
        if (isListening || accelerometer == null) return
        sensorManager.registerListener(
            sensorEventListener,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME
        )
        isListening = true
        Timber.d("SensorGateway: started internal sensor")
    }

    @Synchronized
    fun stopListening() {
        if (!isListening) return
        sensorManager.unregisterListener(sensorEventListener)
        isListening = false
        resetState()
        Timber.d("SensorGateway: stopped internal sensor")
    }

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            if (_isExternalActive.value) return // Block internal bila ESP32 aktif

            // Low-pass gravity filter
            gravity[0] = alpha * event.values[0] + (1 - alpha) * gravity[0]
            gravity[1] = alpha * event.values[1] + (1 - alpha) * gravity[1]
            gravity[2] = alpha * event.values[2] + (1 - alpha) * gravity[2]

            val linearX = event.values[0] - gravity[0]
            val linearY = event.values[1] - gravity[1]
            val linearZ = event.values[2] - gravity[2]

            val xG = linearX / SensorManager.STANDARD_GRAVITY
            val yG = linearY / SensorManager.STANDARD_GRAVITY
            val zG = linearZ / SensorManager.STANDARD_GRAVITY

            _axisX.value = xG
            _axisY.value = yG
            _magnitude.value = sqrt((xG * xG + yG * yG + zG * zG).toDouble()).toFloat()

            processVibrationZ(zG)

            if (BuildConfig.DEBUG) Timber.v("zG=%.3f rms=%.3f", zG, _vibrationFlow.value)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** RMS computation — shared antara internal dan external */
    private fun processVibrationZ(zG: Float) {
        _vibrationRaw.value = zG
        val absZ = abs(zG)
        val square = absZ * absZ

        rmsBuffer.addLast(absZ)
        rmsSumSquares += square
        if (rmsBuffer.size > rmsWindowSize) {
            val removed = rmsBuffer.removeFirst()
            rmsSumSquares -= (removed * removed)
        }

        val rms = if (rmsBuffer.isNotEmpty()) {
            sqrt(rmsSumSquares / rmsBuffer.size).toFloat()
        } else 0f

        _vibrationFlow.value = rms
    }

    private fun resetBuffers() {
        rmsBuffer.clear()
        rmsSumSquares = 0.0
        gravity.fill(0f)
    }

    private fun resetState() {
        _vibrationRaw.value = 0f
        _vibrationFlow.value = 0f
        _axisX.value = 0f
        _axisY.value = 0f
        _magnitude.value = 0f
        resetBuffers()
    }

    fun getLatestVibration(): Float = _vibrationFlow.value
    fun getLatestRawZ(): Float = _vibrationRaw.value
    fun getLatestMagnitude(): Float = _magnitude.value
}