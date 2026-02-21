package zaujaani.roadsensebasic.ui.bluetooth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsensebasic.gateway.BluetoothGateway
import javax.inject.Inject

@HiltViewModel
class BluetoothViewModel @Inject constructor(
    private val bluetoothGateway: BluetoothGateway
) : ViewModel() {

    private val _connectionState = MutableStateFlow<BluetoothGateway.ConnectionState>(
        BluetoothGateway.ConnectionState.Disconnected
    )
    val connectionState: StateFlow<BluetoothGateway.ConnectionState> = _connectionState.asStateFlow()

    private val _devices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val devices: StateFlow<List<BluetoothDeviceInfo>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Raw line untuk debug/log — dari BluetoothGateway_v2.rawLine
    private val _receivedData = MutableStateFlow("")
    val receivedData: StateFlow<String> = _receivedData.asStateFlow()

    // Data terparse ESP32 — dari BluetoothGateway_v2.esp32Data
    private val _esp32Data = MutableStateFlow<BluetoothGateway.Esp32Data?>(null)
    val esp32Data: StateFlow<BluetoothGateway.Esp32Data?> = _esp32Data.asStateFlow()

    data class BluetoothDeviceInfo(
        val name: String,
        val address: String,
        val isBonded: Boolean = false
    )

    init {
        viewModelScope.launch {
            bluetoothGateway.connectionState.collect { state ->
                _connectionState.value = state
            }
        }
        // FIX: rawLine bukan receivedData (nama berubah di BluetoothGateway_v2)
        viewModelScope.launch {
            bluetoothGateway.rawLine.collect { line ->
                if (line.isNotBlank()) {
                    _receivedData.value = line
                }
            }
        }
        // Tambahan: collect data ESP32 terparse
        viewModelScope.launch {
            bluetoothGateway.esp32Data.collect { data: BluetoothGateway.Esp32Data? ->
                _esp32Data.value = data
            }
        }
    }

    fun startScan() {
        viewModelScope.launch {
            _isScanning.value = true
            val bonded = bluetoothGateway.getBondedDevices()
            _devices.value = bonded.map { device ->
                val deviceName = try {
                    device.name?.takeIf { it.isNotBlank() } ?: device.address
                } catch (e: SecurityException) {
                    Timber.w(e, "Cannot read device name")
                    device.address
                }
                BluetoothDeviceInfo(
                    name     = deviceName,
                    address  = device.address,
                    isBonded = true
                )
            }
            _isScanning.value = false
        }
    }

    fun connectToDevice(address: String) {
        bluetoothGateway.connect(address)
    }

    fun disconnect() {
        bluetoothGateway.disconnect()
    }

    fun sendCommand(cmd: String) {
        bluetoothGateway.sendCommand(cmd)
    }

    fun clearReceivedData() {
        _receivedData.value = ""
        _esp32Data.value = null
    }
}