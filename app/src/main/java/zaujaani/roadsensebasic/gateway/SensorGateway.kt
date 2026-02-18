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
 * SensorGateway — manages accelerometer data with:
 * - SENSOR_DELAY_GAME for responsive Z-axis reading
 * - Low-pass filter to remove gravity component
 * - RMS smoothing window for stable vibration score
 * - Separate raw and smoothed flows
 * - Optional listener for real-time events
 * - Rolling sum for efficient RMS computation
 */
@Singleton
class SensorGateway @Inject constructor(
    private val context: Context
) {
    // Configuration parameters (can be made injectable via constructor if needed)
    private var alpha: Float = 0.15f          // low-pass filter factor
    private var rmsWindowSize: Int = 20       // sliding window for RMS

    // Optional callback listener for real-time events
    private var listener: SensorListener? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Low-pass filter state
    private val gravity = FloatArray(3) { 0f }

    // Rolling sum for RMS (sum of squares)
    private var rmsSumSquares = 0.0
    private val rmsBuffer = ArrayDeque<Float>(rmsWindowSize)

    // Raw Z-axis (gravity removed)
    private val _vibrationRaw = MutableStateFlow(0f)
    val vibrationRaw: StateFlow<Float> = _vibrationRaw.asStateFlow()

    // Smoothed RMS Z-axis — primary feed for condition assessment
    private val _vibrationFlow = MutableStateFlow(0f)
    val vibrationFlow: StateFlow<Float> = _vibrationFlow.asStateFlow()

    // X and Y for future full-axis analysis
    private val _axisX = MutableStateFlow(0f)
    val axisX: StateFlow<Float> = _axisX.asStateFlow()

    private val _axisY = MutableStateFlow(0f)
    val axisY: StateFlow<Float> = _axisY.asStateFlow()

    // 3D magnitude (sqrt(x^2 + y^2 + z^2))
    private val _magnitude = MutableStateFlow(0f)
    val magnitude: StateFlow<Float> = _magnitude.asStateFlow()

    // Sensor availability
    val isAccelerometerAvailable: Boolean get() = accelerometer != null

    private var isListening = false

    // ========== CONFIGURATION ==========
    fun setAlpha(alpha: Float) {
        require(alpha in 0f..1f) { "Alpha must be between 0 and 1" }
        this.alpha = alpha
    }

    fun setRmsWindowSize(size: Int) {
        require(size > 0) { "Window size must be positive" }
        rmsWindowSize = size
        // Clear buffer and sum to avoid inconsistent state
        rmsBuffer.clear()
        rmsSumSquares = 0.0
    }

    // Optional listener interface
    interface SensorListener {
        fun onVibrationChanged(rawZ: Float, smoothedRms: Float, magnitude: Float)
    }

    fun setListener(listener: SensorListener?) {
        this.listener = listener
    }

    // ========== LISTENING CONTROL ==========
    @Synchronized
    fun startListening() {
        if (isListening) return
        if (accelerometer == null) {
            Timber.w("Accelerometer not available on this device")
            return
        }
        sensorManager.registerListener(
            sensorEventListener,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME   // ~50Hz — good for road vibration
        )
        isListening = true
        Timber.d("SensorGateway: started listening")
    }

    @Synchronized
    fun stopListening() {
        if (!isListening) return
        sensorManager.unregisterListener(sensorEventListener)
        isListening = false

        // Reset state flows
        _vibrationRaw.value = 0f
        _vibrationFlow.value = 0f
        _axisX.value = 0f
        _axisY.value = 0f
        _magnitude.value = 0f

        // Clear internal buffers
        rmsBuffer.clear()
        rmsSumSquares = 0.0
        gravity.fill(0f)

        Timber.d("SensorGateway: stopped listening and reset state")
    }

    // ========== SENSOR EVENT LISTENER ==========
    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

            // Low-pass filter: isolate gravity
            gravity[0] = alpha * event.values[0] + (1 - alpha) * gravity[0]
            gravity[1] = alpha * event.values[1] + (1 - alpha) * gravity[1]
            gravity[2] = alpha * event.values[2] + (1 - alpha) * gravity[2]

            // High-pass: remove gravity to get linear acceleration
            val linearX = event.values[0] - gravity[0]
            val linearY = event.values[1] - gravity[1]
            val linearZ = event.values[2] - gravity[2]

            // Normalise to g
            val zG = linearZ / SensorManager.STANDARD_GRAVITY
            val xG = linearX / SensorManager.STANDARD_GRAVITY
            val yG = linearY / SensorManager.STANDARD_GRAVITY

            // Compute 3D magnitude
            val mag = sqrt((xG * xG + yG * yG + zG * zG).toDouble()).toFloat()

            _vibrationRaw.value = zG
            _axisX.value = xG
            _axisY.value = yG
            _magnitude.value = mag

            // RMS over sliding window using rolling sum of squares
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

            // Optional verbose logging for debugging
            if (Timber.treeCount > 0 && BuildConfig.DEBUG) {
                Timber.v("zG=%.3f rms=%.3f mag=%.3f", zG, rms, mag)
            }

            // Notify listener if set
            listener?.onVibrationChanged(zG, rms, mag)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Timber.d("Sensor accuracy changed: $accuracy")
        }
    }

    // ========== PUBLIC GETTERS ==========
    /** Latest smoothed vibration value — safe to call from any thread */
    fun getLatestVibration(): Float = _vibrationFlow.value

    /** Latest raw Z linear acceleration in g */
    fun getLatestRawZ(): Float = _vibrationRaw.value

    /** Latest 3D magnitude in g */
    fun getLatestMagnitude(): Float = _magnitude.value
}