package zaujaani.roadsensebasic.ui.bluetooth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.databinding.FragmentBluetoothBinding
import zaujaani.roadsensebasic.gateway.BluetoothGateway

@AndroidEntryPoint
class BluetoothFragment : Fragment() {

    private var _binding: FragmentBluetoothBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BluetoothViewModel by viewModels()

    private lateinit var deviceAdapter: BluetoothDeviceAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBluetoothBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        deviceAdapter = BluetoothDeviceAdapter { device ->
            binding.etDeviceAddress.setText(device.address)
            binding.rvDevices.visibility = View.GONE
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = deviceAdapter
        }
    }

    private fun setupListeners() {
        binding.btnScan.setOnClickListener {
            viewModel.startScan()
        }

        binding.btnConnect.setOnClickListener {
            val address = binding.etDeviceAddress.text.toString().trim()
            if (address.isNotBlank()) {
                viewModel.connectToDevice(address)
            } else {
                Toast.makeText(requireContext(), R.string.enter_mac_address, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDisconnect.setOnClickListener {
            viewModel.disconnect()
        }

        binding.btnSendCommand.setOnClickListener {
            val cmd = binding.etCommand.text.toString().trim()
            if (cmd.isNotBlank()) {
                viewModel.sendCommand(cmd)
                binding.etCommand.text?.clear()
            }
        }

        binding.btnClearData.setOnClickListener {
            viewModel.clearReceivedData()
            binding.tvReceivedData.text = ""
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.devices.collect { devices ->
                        if (devices.isNotEmpty()) {
                            deviceAdapter.submitList(devices)
                            binding.rvDevices.visibility = View.VISIBLE
                        } else {
                            binding.rvDevices.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.isScanning.collect { scanning ->
                        binding.btnScan.isEnabled = !scanning
                        binding.btnScan.text = if (scanning)
                            getString(R.string.connecting)
                        else
                            getString(R.string.scan_devices)
                    }
                }

                launch {
                    viewModel.connectionState.collect { state ->
                        when (state) {
                            is BluetoothGateway.ConnectionState.Connected -> {
                                binding.tvStatus.text = getString(R.string.connected)
                                binding.tvStatus.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.green)
                                )
                                binding.btnDisconnect.isEnabled = true
                                binding.btnConnect.isEnabled = false
                                binding.btnSendCommand.isEnabled = true
                            }
                            is BluetoothGateway.ConnectionState.Connecting -> {
                                binding.tvStatus.text = getString(R.string.connecting)
                                binding.tvStatus.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.yellow)
                                )
                                binding.btnConnect.isEnabled = false
                                binding.btnDisconnect.isEnabled = false
                                binding.btnSendCommand.isEnabled = false
                            }
                            is BluetoothGateway.ConnectionState.Disconnected -> {
                                binding.tvStatus.text = getString(R.string.disconnected)
                                binding.tvStatus.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.red)
                                )
                                binding.btnDisconnect.isEnabled = false
                                binding.btnConnect.isEnabled = true
                                binding.btnSendCommand.isEnabled = false
                            }
                            is BluetoothGateway.ConnectionState.Error -> {
                                binding.tvStatus.text = "${getString(R.string.error)}: ${state.message}"
                                binding.tvStatus.setTextColor(
                                    ContextCompat.getColor(requireContext(), R.color.red)
                                )
                                binding.btnConnect.isEnabled = true
                                binding.btnDisconnect.isEnabled = false
                                binding.btnSendCommand.isEnabled = false
                            }
                        }
                    }
                }

                launch {
                    viewModel.receivedData.collect { data ->
                        if (data.isNotBlank()) {
                            binding.tvReceivedData.append("$data\n")
                            binding.scrollViewData.post {
                                binding.scrollViewData.fullScroll(View.FOCUS_DOWN)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}