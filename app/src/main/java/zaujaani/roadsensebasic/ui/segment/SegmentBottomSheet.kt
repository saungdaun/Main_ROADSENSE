package zaujaani.roadsensebasic.ui.segment

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.databinding.FragmentSegmentBottomSheetBinding
import zaujaani.roadsensebasic.util.Constants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@AndroidEntryPoint
class SegmentBottomSheet : BottomSheetDialogFragment() {

    private var _binding: FragmentSegmentBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SegmentViewModel by viewModels()
    private val args: SegmentBottomSheetArgs by navArgs()

    // Camera
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var currentPhotoPath: String? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Voice
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioFilePath: String? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private var recordingTimerJob: kotlinx.coroutines.Job? = null

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.permission_denied, denied.joinToString()), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSegmentBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        checkPermissions()
        setupDropdowns()
        setupConfidence()
        setupClickListeners()
        observeViewModel()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun setupDropdowns() {
        val conditionAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            listOf(
                Constants.CONDITION_BAIK,
                Constants.CONDITION_SEDANG,
                Constants.CONDITION_RUSAK_RINGAN,
                Constants.CONDITION_RUSAK_BERAT
            )
        )
        binding.actvCondition.setAdapter(conditionAdapter)

        val surfaceAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            Constants.SURFACE_TYPES
        )
        binding.actvSurface.setAdapter(surfaceAdapter)

        // Set default values dari arguments (yang berasal dari temporary pilihan di MapFragment)
        binding.actvCondition.setText(args.tempCondition, false)
        binding.actvSurface.setText(args.tempSurface, false)
    }

    private fun setupConfidence() {
        binding.tvConfidence.text = getString(R.string.confidence_value, args.confidence)
        val validationMessage = when {
            args.confidence < 50 -> getString(R.string.gps_accuracy_low)
            args.confidence < 70 -> getString(R.string.gps_accuracy_fair)
            else -> getString(R.string.gps_accuracy_good)
        }
        binding.tvValidation.text = validationMessage
    }

    private fun setupClickListeners() {
        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnTakePhoto.setOnClickListener {
            openCamera()
        }

        binding.btnVoice.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        binding.btnStopRecording.setOnClickListener {
            stopRecording()
        }

        binding.btnPlayVoice.setOnClickListener {
            playRecording()
        }

        binding.btnSave.setOnClickListener {
            saveSegment()
        }
    }

    // ========== KAMERA ==========
    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(requireContext(), R.string.camera_permission_required, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_camera, null)
        val previewView = dialogView.findViewById<PreviewView>(R.id.previewView)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.take_photo)
            .setView(dialogView)
            .setPositiveButton(R.string.take) { _, _ ->
                takePhoto()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        startCamera(previewView)
    }

    private fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                Timber.e(e, "Gagal memulai kamera")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    currentPhotoPath = photoFile.absolutePath
                    binding.ivPhotoPreview.visibility = View.VISIBLE
                    binding.ivPhotoPreview.setImageURI(Uri.fromFile(photoFile))
                    Toast.makeText(requireContext(), R.string.photo_saved, Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Timber.e(exception, "Gagal mengambil foto")
                    Toast.makeText(requireContext(), R.string.photo_failed, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ========== VOICE RECORDING ==========
    @SuppressLint("NewApi")
    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(requireContext(), R.string.microphone_permission_required, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val audioFile = File(
                requireContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis()) + ".3gp"
            )
            audioFilePath = audioFile.absolutePath

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                MediaRecorder(requireContext())
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFilePath)
                prepare()
                start()
            }
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            startRecordingTimer()

            // Update UI
            binding.btnVoice.visibility = View.GONE
            binding.layoutRecording.visibility = View.VISIBLE
            binding.btnPlayVoice.visibility = View.GONE
        } catch (e: Exception) {
            Timber.e(e, "Gagal memulai rekaman")
            Toast.makeText(requireContext(), R.string.recording_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
        } catch (e: Exception) {
            Timber.e(e, "Error stop recording")
        }
        isRecording = false
        recordingTimerJob?.cancel()

        // Update UI
        binding.btnVoice.visibility = View.VISIBLE
        binding.layoutRecording.visibility = View.GONE
        if (audioFilePath != null) {
            binding.btnPlayVoice.visibility = View.VISIBLE
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob = lifecycleScope.launch {
            while (isRecording) {
                val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                val minutes = elapsed / 60
                val seconds = elapsed % 60
                binding.tvRecordingTime.text = String.format(Locale.US, "%02d:%02d", minutes, seconds)
                delay(1000)
            }
        }
    }

    private fun playRecording() {
        if (audioFilePath == null) return

        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            binding.btnPlayVoice.setText(R.string.play_recording)
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFilePath)
                prepare()
                start()
                setOnCompletionListener {
                    binding.btnPlayVoice.setText(R.string.play_recording)
                }
            }
            binding.btnPlayVoice.setText(R.string.stop)
        } catch (e: Exception) {
            Timber.e(e, "Gagal memutar audio")
            Toast.makeText(requireContext(), R.string.play_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ========== SIMPAN SEGMEN ==========
    private fun saveSegment() {
        val roadName = binding.etRoadName.text?.toString()?.trim() ?: ""
        if (roadName.isEmpty()) {
            binding.etRoadName.error = getString(R.string.road_name_required)
            return
        }

        val condition = binding.actvCondition.text?.toString() ?: args.conditionAuto
        val surface = binding.actvSurface.text?.toString() ?: getString(R.string.default_surface)
        val notes = binding.etNotes.text?.toString() ?: ""

        viewModel.saveSegment(
            sessionId = args.sessionId,
            startDistance = args.startDistance,
            endDistance = args.endDistance,
            avgVibration = args.avgVibration,
            conditionAuto = args.conditionAuto,
            confidence = args.confidence,
            roadName = roadName,
            manualCondition = condition,
            surfaceType = surface,
            notes = notes,
            photoPath = currentPhotoPath ?: "",
            audioPath = audioFilePath ?: ""
        )
    }

    private fun observeViewModel() {
        viewModel.saveResult.observe(viewLifecycleOwner) { success ->
            if (success) {
                Toast.makeText(requireContext(), R.string.segment_saved, Toast.LENGTH_SHORT).show()
                setFragmentResult("segment_saved", bundleOf())
                dismiss()
            } else {
                Toast.makeText(requireContext(), R.string.segment_save_failed, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.isSaving.observe(viewLifecycleOwner) { isSaving ->
            binding.btnSave.isEnabled = !isSaving
            binding.progressBar.visibility = if (isSaving) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
        mediaRecorder?.release()
        mediaPlayer?.release()
        _binding = null
    }
}