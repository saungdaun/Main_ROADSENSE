package zaujaani.roadsensebasic.gateway

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensorGateway @Inject constructor(
    private val context: Context
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _vibration = MutableStateFlow(0f)
    val vibrationFlow: StateFlow<Float> = _vibration.asStateFlow()

    private val _latestVibration = MutableStateFlow(0f)
    val latestVibration: StateFlow<Float> = _latestVibration.asStateFlow()

    fun getLatestVibration(): Float = _latestVibration.value

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val z = it.values[2] / SensorManager.STANDARD_GRAVITY // konversi ke g
                    _vibration.value = z
                    _latestVibration.value = z
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun startListening() {
        accelerometer?.let {
            sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(sensorEventListener)
    }
}