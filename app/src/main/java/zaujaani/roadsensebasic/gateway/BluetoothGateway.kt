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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothGateway @Inject constructor(
    private val context: Context
) {
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(BluetoothManager::class.java)
        manager?.adapter
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var readingJob: Job? = null
    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedData = MutableStateFlow("")
    val receivedData: StateFlow<String> = _receivedData.asStateFlow()

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val device: BluetoothDevice) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    // ─── Permission helper ────────────────────────────────────────────────────

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun getBondedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermission()) {
            Timber.w("BLUETOOTH_CONNECT permission not granted")
            return emptyList()
        }
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException getting bonded devices")
            emptyList()
        }
    }

    /**
     * Menghubungkan ke device secara blocking di IO dispatcher.
     * Setelah konek berhasil, memulai loop baca data dari socket.
     */
    fun connect(address: String) {
        if (!hasBluetoothPermission()) {
            _connectionState.value = ConnectionState.Error("Bluetooth permission not granted")
            return
        }

        gatewayScope.launch {
            _connectionState.value = ConnectionState.Connecting

            try {
                val device = bluetoothAdapter?.getRemoteDevice(address)
                    ?: run {
                        _connectionState.value = ConnectionState.Error("Device not found")
                        return@launch
                    }

                // Batalkan discovery agar konek lebih cepat
                try {
                    bluetoothAdapter?.cancelDiscovery()
                } catch (e: SecurityException) {
                    Timber.w(e, "Could not cancel discovery")
                }

                // Tutup socket lama jika ada
                closeSocket()

                val socket = try {
                    device.createRfcommSocketToServiceRecord(sppUuid)
                } catch (e: SecurityException) {
                    _connectionState.value = ConnectionState.Error("Permission denied creating socket")
                    return@launch
                }

                bluetoothSocket = socket
                socket.connect() // blocking – aman karena di Dispatchers.IO

                _connectionState.value = ConnectionState.Connected(device)
                Timber.d("Bluetooth connected to $address")

                // Mulai baca data
                startReading(socket)

            } catch (e: IOException) {
                Timber.e(e, "Bluetooth connection failed")
                _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
                closeSocket()
            } catch (e: SecurityException) {
                Timber.e(e, "SecurityException during connect")
                _connectionState.value = ConnectionState.Error("Permission denied")
                closeSocket()
            }
        }
    }

    fun disconnect() {
        readingJob?.cancel()
        readingJob = null
        closeSocket()
        _connectionState.value = ConnectionState.Disconnected
        Timber.d("Bluetooth disconnected")
    }

    fun sendCommand(command: String) {
        if (bluetoothSocket?.isConnected != true) {
            Timber.w("Not connected, cannot send command")
            return
        }
        gatewayScope.launch {
            try {
                bluetoothSocket?.outputStream?.let { stream ->
                    stream.write((command + "\n").toByteArray(Charsets.UTF_8))
                    stream.flush()
                    Timber.d("Command sent: $command")
                }
            } catch (e: IOException) {
                Timber.e(e, "Failed to send command")
                _connectionState.value = ConnectionState.Error(e.message ?: "Send failed")
            }
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Loop membaca data dari InputStream socket secara terus-menerus.
     * Berjalan di coroutine terpisah hingga socket ditutup.
     */
    private fun startReading(socket: BluetoothSocket) {
        readingJob?.cancel()
        readingJob = gatewayScope.launch {
            val buffer = ByteArray(1024)
            Timber.d("Reading loop started")
            try {
                val inputStream = socket.inputStream
                while (true) {
                    val bytesRead = withContext(Dispatchers.IO) {
                        inputStream.read(buffer) // blocking
                    }
                    if (bytesRead > 0) {
                        val received = String(buffer, 0, bytesRead, Charsets.UTF_8).trim()
                        Timber.d("Received: $received")
                        _receivedData.value = received
                    }
                }
            } catch (e: IOException) {
                Timber.w(e, "Reading loop ended")
                // Jika masih dalam state Connected, berarti koneksi putus tiba-tiba
                if (_connectionState.value is ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Error("Connection lost")
                }
            }
        }
    }

    private fun closeSocket() {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Timber.e(e, "Error closing socket")
        }
        bluetoothSocket = null
    }
}