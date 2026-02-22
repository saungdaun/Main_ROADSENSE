package zaujaani.roadsensebasic.ui.distress

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.PCIDistressType
import zaujaani.roadsensebasic.data.local.entity.Severity
import zaujaani.roadsensebasic.databinding.BottomSheetDistressBinding
import zaujaani.roadsensebasic.util.MediaManager

@AndroidEntryPoint
class PciDistressBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDistressBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DistressViewModel by viewModels()

    private var selectedType: PCIDistressType? = null
    private var selectedSeverity: Severity? = null
    private var currentPhotoPath: String? = null
    private var currentAudioPath: String? = null
    private lateinit var mediaManager: MediaManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDistressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initMediaManager()
        setupDropdowns()
        setupListeners()
        refreshQuickButtons()

        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is SaveResult.Success -> {
                    Toast.makeText(requireContext(), R.string.distress_saved, Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                is SaveResult.Error -> Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                SaveResult.Loading -> binding.btnSave.isEnabled = false
            }
        }
    }

    private fun initMediaManager() {
        mediaManager = MediaManager(
            fragment = this,
            lifecycleScope = lifecycleScope,
            onPhotoTaken = { path, _ ->
                currentPhotoPath = path
                binding.ivPhotoThumb.visibility = View.VISIBLE
                binding.ivPhotoThumb.setImageURI(android.net.Uri.fromFile(java.io.File(path)))
            },
            onVoiceRecorded = { path ->
                currentAudioPath = path
                // Opsional: tampilkan indikator rekaman selesai
            },
            metadataProvider = {
                val location = viewModel.getCurrentLocation()
                MediaManager.PhotoMetadata(
                    distanceMeters = viewModel.getCurrentDistance(),
                    latitude = location?.latitude ?: 0.0,
                    longitude = location?.longitude ?: 0.0,
                    roadName = viewModel.getRoadName(),
                    surveyMode = "PCI"
                )
            }
        )
    }

    private fun setupDropdowns() {
        // Jenis Kerusakan PCI
        val typeAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            PCIDistressType.entries.map { it.displayName }
        )
        binding.actvDistressType.setAdapter(typeAdapter)
        binding.actvDistressType.setOnItemClickListener { _, _, position, _ ->
            selectedType = PCIDistressType.entries[position]
            onDistressTypeSelected(selectedType!!)
        }

        // Tingkat Keparahan
        val severityAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            Severity.entries.map { it.displayName }
        )
        binding.actvSeverity.setAdapter(severityAdapter)
        binding.actvSeverity.setOnItemClickListener { _, _, position, _ ->
            selectedSeverity = Severity.entries[position]
        }
    }

    private fun onDistressTypeSelected(type: PCIDistressType) {
        binding.tvUnitLabel.text = type.unitLabel
        binding.tvSurveyorGuide.text = type.getSurveyorGuide()
        binding.tvSurveyorGuide.visibility = View.VISIBLE
        binding.etLengthArea.text?.clear()
        refreshQuickButtons()
    }

    private fun refreshQuickButtons() {
        val presets = selectedType?.getQuickPresets() ?: listOf(
            "1" to 1.0,
            "5" to 5.0,
            "10" to 10.0,
            "20" to 20.0
        )
        val buttons = listOf(
            binding.btnQuick5,
            binding.btnQuick10,
            binding.btnQuick20,
            binding.btnQuick50
        )
        presets.forEachIndexed { index, (label, value) ->
            buttons.getOrNull(index)?.apply {
                text = label
                setOnClickListener {
                    binding.etLengthArea.setText(value.toString())
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnAddPhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                mediaManager.openCamera()
            } else {
                requestCameraPermission()
            }
        }

        binding.btnVoice.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                mediaManager.toggleVoiceRecording()
            } else {
                requestAudioPermission()
            }
        }

        binding.btnSave.setOnClickListener { saveDistress() }
    }

    private fun saveDistress() {
        val type = selectedType
        val severity = selectedSeverity
        val quantityStr = binding.etLengthArea.text.toString().trim()
        val notes = binding.etNotes.text.toString().trim()

        if (type == null) {
            Toast.makeText(requireContext(), R.string.select_distress_type, Toast.LENGTH_SHORT).show()
            return
        }
        if (severity == null) {
            Toast.makeText(requireContext(), R.string.select_severity, Toast.LENGTH_SHORT).show()
            return
        }
        if (quantityStr.isBlank()) {
            Toast.makeText(requireContext(), R.string.enter_length_area, Toast.LENGTH_SHORT).show()
            return
        }
        val quantity = quantityStr.toDoubleOrNull()
        if (quantity == null || quantity <= 0) {
            Toast.makeText(requireContext(), R.string.invalid_data, Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.savePciDistress(
            type = type,
            severity = severity,
            quantity = quantity,
            photoPath = currentPhotoPath ?: "",
            audioPath = currentAudioPath ?: "",
            notes = notes
        )
    }

    private val cameraPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) mediaManager.openCamera()
        else Toast.makeText(requireContext(), R.string.camera_permission_required, Toast.LENGTH_SHORT).show()
    }

    private val audioPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) mediaManager.toggleVoiceRecording()
        else Toast.makeText(requireContext(), R.string.microphone_permission_required, Toast.LENGTH_SHORT).show()
    }

    private fun requestCameraPermission() {
        cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun requestAudioPermission() {
        audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mediaManager.release()
        _binding = null
    }
}