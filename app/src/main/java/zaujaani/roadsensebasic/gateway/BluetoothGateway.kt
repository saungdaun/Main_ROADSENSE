package zaujaani.roadsensebasic.gateway

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import zaujaani.roadsensebasic.util.Constants
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BluetoothGateway v2.1 — ESP32 SPP Connection Manager
 *
 * FIX v2.1 (Bug #4):
 *   feedExternalAux(vBat, temp) sekarang dipanggil setelah parse data.
 *   Sebelumnya ESP32 battery dan temperature tidak pernah masuk SensorGateway,
 *   menyebabkan widget baterai selalu tampil 0V dan suhu 0°C.
 *
 * FIX v2.1 (minor):
 *   Menggunakan Constants.ESP32_SPP_UUID alih-alih duplikasi di private object.
 *   Menghapus Constants_BT private object yang redundan.
 */
@Singleton
class BluetoothGateway @Inject constructor(
    private val context: Context,
    private val sensorGateway: SensorGateway
) {
    // FIX: gunakan Constants.ESP32_SPP_UUID (tidak duplikasi lagi)
    private val sppUuid = UUID.fromString(Constants.ESP32_SPP_UUID)

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(BluetoothManager::class.java)
        manager?.adapter
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var readingJob: Job? = null
    private var reconnectJob: Job? = null
    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val device: BluetoothDevice) : ConnectionState()
        data class Reconnecting(val attempt: Int) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    data class Esp32Data(
        val timestamp: Long,
        val pulseCount: Long,
        val accelZ: Float,
        val batteryVoltage: Float,
        val temperature: Float,
        val speedKmh: Float = 0f
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _esp32Data = MutableStateFlow<Esp32Data?>(null)
    val esp32Data: StateFlow<Esp32Data?> = _esp32Data.asStateFlow()

    private val _rawLine = MutableStateFlow("")
    val rawLine: StateFlow<String> = _rawLine.asStateFlow()

    val isExternalSensorActive: Boolean
        get() = _connectionState.value is ConnectionState.Connected

    var wheelCircumferenceMeter: Double = 1.885
    var pulsesPerRevolution: Int = 20

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    fun getBondedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException getting bonded devices")
            emptyList()
        }
    }

    fun connect(address: String) {
        if (!hasBluetoothPermission()) {
            _connectionState.value = ConnectionState.Error("Bluetooth permission not granted")
            return
        }
        reconnectAttempts = 0
        connectInternal(address)
    }

    private fun connectInternal(address: String) {
        gatewayScope.launch {
            _connectionState.value = ConnectionState.Connecting
            try {
                val device = bluetoothAdapter?.getRemoteDevice(address)
                    ?: run {
                        _connectionState.value = ConnectionState.Error("Device not found: $address")
                        return@launch
                    }

                @Suppress("DEPRECATION")
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothAdapter?.cancelDiscovery()
                socket.connect()

                bluetoothSocket = socket
                _connectionState.value = ConnectionState.Connected(device)
                reconnectAttempts = 0
                Timber.i("BT Connected to ${device.name}")

                sensorGateway.setExternalSensorActive(true)
                Timber.i("Internal sensor PAUSED — using ESP32")

                startReading(address)
            } catch (e: IOException) {
                Timber.e(e, "BT connect failed")
                handleDisconnect(address)
            } catch (e: SecurityException) {
                Timber.e(e, "BT SecurityException")
                _connectionState.value = ConnectionState.Error("Bluetooth permission revoked")
            }
        }
    }

    private fun startReading(address: String) {
        readingJob?.cancel()
        readingJob = gatewayScope.launch {
            val buffer = StringBuilder()
            val inputStream = try {
                bluetoothSocket?.inputStream ?: run {
                    handleDisconnect(address)
                    return@launch
                }
            } catch (e: IOException) {
                handleDisconnect(address)
                return@launch
            }

            val byteArray = ByteArray(1024)
            while (true) {
                try {
                    val bytes = inputStream.read(byteArray)
                    if (bytes <= 0) break
                    val chunk = String(byteArray, 0, bytes)
                    buffer.append(chunk)

                    var newlineIdx: Int
                    while (buffer.indexOf('\n').also { newlineIdx = it } >= 0) {
                        val line = buffer.substring(0, newlineIdx).trim()
                        buffer.delete(0, newlineIdx + 1)
                        if (line.isNotEmpty()) {
                            _rawLine.value = line
                            parseEsp32Line(line)
                        }
                    }
                } catch (e: IOException) {
                    Timber.w("BT read error: ${e.message}")
                    break
                }
            }
            handleDisconnect(address)
        }
    }

    private fun parseEsp32Line(line: String) {
        // Format: RS2,<timestamp>,<pulseCount>,<accelZ>,<battVoltage>,<temp>
        if (!line.startsWith("RS2,")) return
        try {
            val parts = line.split(",")
            if (parts.size < 6) return

            val ts     = parts[1].toLongOrNull()  ?: return
            val pulse  = parts[2].toLongOrNull()  ?: return
            val accelZ = parts[3].toFloatOrNull() ?: return
            val vBat   = parts[4].toFloatOrNull() ?: return
            val temp   = parts[5].toFloatOrNull() ?: return

            val data = Esp32Data(
                timestamp      = ts,
                pulseCount     = pulse,
                accelZ         = accelZ,
                batteryVoltage = vBat,
                temperature    = temp
            )
            _esp32Data.value = data

            // Feed vibration ke pipeline sensor
            sensorGateway.feedExternalVibration(accelZ)

            // FIX #4: feed baterai & suhu (sebelumnya HILANG, selalu 0V dan 0°C)
            sensorGateway.feedExternalAux(vBat, temp)

        } catch (e: Exception) {
            Timber.w("ESP32 parse error: $line")
        }
    }

    private suspend fun handleDisconnect(address: String) {
        closeSafely()

        sensorGateway.setExternalSensorActive(false)
        Timber.i("ESP32 disconnected — internal sensor RESUMED")

        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            val delayMs = minOf(2000L * reconnectAttempts, 30000L)
            _connectionState.value = ConnectionState.Reconnecting(reconnectAttempts)
            Timber.i("Reconnecting in ${delayMs}ms (attempt $reconnectAttempts)")
            delay(delayMs)
            connectInternal(address)
        } else {
            _connectionState.value = ConnectionState.Error("Koneksi terputus setelah $maxReconnectAttempts percobaan")
            reconnectAttempts = 0
        }
    }

    fun disconnect() {
        reconnectAttempts = maxReconnectAttempts
        readingJob?.cancel()
        reconnectJob?.cancel()
        gatewayScope.launch { closeSafely() }
        sensorGateway.setExternalSensorActive(false)
        _connectionState.value = ConnectionState.Disconnected
        _esp32Data.value = null
    }

    private suspend fun closeSafely() = withContext(Dispatchers.IO) {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Timber.w("Error closing socket: ${e.message}")
        }
        bluetoothSocket = null
    }

    fun sendCommand(command: String) {
        gatewayScope.launch {
            try {
                val out = bluetoothSocket?.outputStream ?: return@launch
                out.write("$command\n".toByteArray())
                out.flush()
                Timber.d("BT Command sent: $command")
            } catch (e: IOException) {
                Timber.e(e, "Failed to send command: $command")
            }
        }
    }

    fun pulseToDistance(pulseCount: Long): Double {
        val revolutions = pulseCount.toDouble() / pulsesPerRevolution
        return revolutions * wheelCircumferenceMeter
    }
}