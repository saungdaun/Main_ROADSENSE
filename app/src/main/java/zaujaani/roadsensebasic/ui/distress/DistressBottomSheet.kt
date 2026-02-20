package zaujaani.roadsensebasic.ui.distress

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsensebasic.BuildConfig
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.DistressType
import zaujaani.roadsensebasic.data.local.entity.Severity
import zaujaani.roadsensebasic.databinding.BottomSheetDistressBinding
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class DistressBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDistressBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DistressViewModel by viewModels()

    // State
    private var selectedType: DistressType? = null
    private var selectedSeverity: Severity? = null
    private var currentPhotoPath: String? = null
    private var currentAudioFile: File? = null
    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var recordingTimerJob: Job? = null
    private var recordingSeconds = 0

    // Camera
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && currentPhotoPath != null) {
                binding.ivPhotoThumb.visibility = View.VISIBLE
                val bitmap = BitmapFactory.decodeFile(currentPhotoPath)
                binding.ivPhotoThumb.setImageBitmap(bitmap)
                scanFile(currentPhotoPath!!)
                Toast.makeText(requireContext(), "Foto tersimpan", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Gagal mengambil foto", Toast.LENGTH_SHORT).show()
                currentPhotoPath = null
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
            else Toast.makeText(requireContext(), "Izin kamera diperlukan", Toast.LENGTH_SHORT).show()
        }

    // Voice
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording()
            else Toast.makeText(requireContext(), "Izin mikrofon diperlukan", Toast.LENGTH_SHORT).show()
        }

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

        setupDropdowns()
        setupToggleGroup()
        setupQuickButtons()
        setupListeners()
        updateUnitLabel()

        // Observe save result with viewLifecycleOwner
        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is SaveResult.Success -> {
                    Toast.makeText(requireContext(), "Kerusakan tersimpan", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                is SaveResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
                SaveResult.Loading -> {
                    // Optional: show loading indicator
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isRecording) stopRecording()
        mediaRecorder?.release()
        recordingTimerJob?.cancel()
        _binding = null
    }

    private fun setupDropdowns() {
        val distressAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            DistressType.values().map { it.displayName } // pakai displayName
        )
        binding.actvDistressType.setAdapter(distressAdapter)

        binding.actvDistressType.setOnItemClickListener { _, _, position, _ ->
            selectedType = DistressType.values()[position] // pakai values()
            updateUnitLabel()
        }

        // Kadang perlu trigger manual
        binding.actvDistressType.setOnClickListener {
            binding.actvDistressType.showDropDown()
        }
    }

    private fun setupToggleGroup() {
        binding.toggleSeverity.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedSeverity = when (checkedId) {
                    R.id.btnLow -> Severity.LOW
                    R.id.btnMedium -> Severity.MEDIUM
                    R.id.btnHigh -> Severity.HIGH
                    else -> null
                }
            }
        }
    }

    private fun setupQuickButtons() {
        binding.btnQuick5.setOnClickListener { binding.etLengthArea.setText("5") }
        binding.btnQuick10.setOnClickListener { binding.etLengthArea.setText("10") }
        binding.btnQuick20.setOnClickListener { binding.etLengthArea.setText("20") }
        binding.btnQuick50.setOnClickListener { binding.etLengthArea.setText("50") }
    }

    private fun setupListeners() {
        binding.btnAddPhoto.setOnClickListener { openCamera() }
        binding.btnVoice.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
        binding.btnSave.setOnClickListener { saveDistress() }
    }

    private fun updateUnitLabel() {
        val unit = when (selectedType) {
            DistressType.CRACK -> "Panjang (m)"
            DistressType.POTHole -> "Luas (m²)"
            else -> "Panjang/Luas"
        }
        binding.tvUnitLabel.text = unit
    }

    // Camera methods
    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            val photoFile = createImageFile()
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                photoFile
            )
            takePictureLauncher.launch(uri)
        } catch (e: IOException) {
            Timber.e(e, "Error creating photo file")
            Toast.makeText(requireContext(), "Gagal membuat file foto", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File(storageDir, "ROADSENSE_${timestamp}.jpg")
        currentPhotoPath = file.absolutePath
        return file
    }

    private fun scanFile(path: String) {
        MediaScannerConnection.scanFile(requireContext(), arrayOf(path), null, null)
    }

    // Voice methods
    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val audioDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val audioFile = File(audioDir, "ROADSENSE_AUDIO_${timestamp}.mp4")
            currentAudioFile = audioFile

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(requireContext())
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            recordingSeconds = 0
            startRecordingTimer()
            binding.btnVoice.text = "Stop"
            binding.btnVoice.setIconResource(R.drawable.ic_stop)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording")
            Toast.makeText(requireContext(), "Gagal memulai rekaman", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
            }
            mediaRecorder = null
            isRecording = false
            recordingTimerJob?.cancel()
            binding.btnVoice.text = "Rekam"
            binding.btnVoice.setIconResource(R.drawable.ic_mic)
            if (currentAudioFile != null) {
                Toast.makeText(requireContext(), "Rekaman tersimpan", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping recording")
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = lifecycleScope.launch {
            while (isRecording) {
                recordingSeconds++
                delay(1000L)
            }
        }
    }

    // Save
    private fun saveDistress() {
        val typeStr = binding.actvDistressType.text.toString()
        val lengthAreaStr = binding.etLengthArea.text.toString()
        val notes = binding.etNotes.text.toString()

        if (typeStr.isBlank()) {
            Toast.makeText(requireContext(), "Pilih jenis kerusakan", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedSeverity == null) {
            Toast.makeText(requireContext(), "Pilih tingkat keparahan", Toast.LENGTH_SHORT).show()
            return
        }
        if (lengthAreaStr.isBlank()) {
            Toast.makeText(requireContext(), "Masukkan panjang/luas", Toast.LENGTH_SHORT).show()
            return
        }

        // Cari enum berdasarkan displayName (karena kita pakai displayName di dropdown)
        val type = DistressType.values().find { it.displayName == typeStr }
        val lengthArea = lengthAreaStr.toDoubleOrNull()

        if (type == null || lengthArea == null) {
            Toast.makeText(requireContext(), "Data tidak valid", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.saveDistress(
            type = type,
            severity = selectedSeverity!!,
            lengthOrArea = lengthArea,
            photoPath = currentPhotoPath ?: "",
            audioPath = currentAudioFile?.absolutePath ?: "",
            notes = notes
        )
    }
}