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

    private val _receivedData = MutableStateFlow("")
    val receivedData: StateFlow<String> = _receivedData.asStateFlow()

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
        viewModelScope.launch {
            bluetoothGateway.receivedData.collect { data ->
                if (data.isNotBlank()) {
                    _receivedData.value = data
                }
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
                    name = deviceName,
                    address = device.address,
                    isBonded = true
                )
            }
            _isScanning.value = false
        }
    }

    fun connectToDevice(address: String) {
        // Gateway mengelola coroutine-nya sendiri di gatewayScope
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
    }
}