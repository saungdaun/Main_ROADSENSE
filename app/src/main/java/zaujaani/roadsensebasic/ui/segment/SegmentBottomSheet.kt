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
import zaujaani.roadsensebasic.domain.engine.SurveyEngine
import zaujaani.roadsensebasic.util.Constants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class SegmentBottomSheet : BottomSheetDialogFragment() {

    private var _binding: FragmentSegmentBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SegmentViewModel by viewModels()
    private val args: SegmentBottomSheetArgs by navArgs()

    @Inject
    lateinit var surveyEngine: SurveyEngine

    // Camera
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var currentPhotoPath: String? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraDialog: androidx.appcompat.app.AlertDialog? = null
    private var isTakingPhoto = false

    // Voice
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioFilePath: String? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private var recordingTimerJob: kotlinx.coroutines.Job? = null

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.permission_denied, denied.joinToString()),
                Toast.LENGTH_SHORT
            ).show()
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
        cameraExecutor = Executors.newSingleThreadExecutor()
        checkPermissions()
        setupDropdowns()
        setupSensorInfo()
        setupClickListeners()
        observeViewModel()
    }

    private fun checkPermissions() {
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val notGranted = needed.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
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

        binding.actvCondition.setText(args.tempCondition, false)
        binding.actvSurface.setText(args.tempSurface, false)
    }

    private fun setupSensorInfo() {
        binding.tvConfidence.text = getString(R.string.confidence_value, args.confidence)

        val (validMsg, validColor) = when {
            args.confidence < 50 -> Pair(getString(R.string.gps_accuracy_low), R.color.red)
            args.confidence < 70 -> Pair(getString(R.string.gps_accuracy_fair), R.color.yellow)
            else -> Pair(getString(R.string.gps_accuracy_good), R.color.green)
        }
        binding.tvValidation.text = validMsg
        binding.tvValidation.setTextColor(ContextCompat.getColor(requireContext(), validColor))

        val startDist = args.startDistance.toInt()
        val endDist = args.endDistance.toInt()
        val length = endDist - startDist
        binding.tvSegmentInfo.text = getString(
            R.string.segment_info_format,
            startDist,
            endDist,
            length,
            String.format(Locale.US, "%.3f", args.avgVibration), // ✅ pakai Locale eksplisit
            args.conditionAuto
        )

    }

    private fun setupClickListeners() {
        binding.btnCancel.setOnClickListener {
            surveyEngine.cancelSegment()
            dismiss()
        }

        binding.btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                openCameraDialog()
            } else {
                Toast.makeText(requireContext(), getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnVoice.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        binding.btnStopRecording.setOnClickListener { stopRecording() }

        binding.btnPlayVoice.setOnClickListener { playRecording() }

        binding.btnSave.setOnClickListener { saveSegment() }
    }

    // ========== KAMERA ==========

    private fun openCameraDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_camera, null)
        val previewView = dialogView.findViewById<PreviewView>(R.id.previewView)

        cameraDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.take_photo))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.take)) { _, _ -> takePhoto() }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> cameraDialog?.dismiss() }
            .setOnDismissListener { releaseCamera() }
            .show()

        // Nonaktifkan tombol ambil foto sampai kamera benar-benar siap
        cameraDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = false

        startCamera(previewView)
    }

    private fun startCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()


                // Bind ke viewLifecycleOwner (lebih aman di fragment/bottomsheet)
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )

                // Kamera siap → aktifkan tombol ambil foto
                cameraDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                Timber.i("Kamera berhasil dibind ke lifecycle")
            } catch (e: Exception) {
                Timber.e(e, "Gagal bind kamera (mungkin quirk Xiaomi)")
                Toast.makeText(requireContext(), "Gagal membuka kamera: ${e.message}", Toast.LENGTH_SHORT).show()
                cameraDialog?.dismiss()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        if (isTakingPhoto) {
            Timber.d("takePhoto skipped: sedang proses")
            return
        }
        isTakingPhoto = true

        val capture = imageCapture ?: run {
            Timber.w("takePhoto dipanggil tapi imageCapture null")
            Toast.makeText(requireContext(), "Kamera belum siap", Toast.LENGTH_SHORT).show()
            isTakingPhoto = false
            return
        }

        Timber.i("Mulai capture foto - mode MINIMIZE_LATENCY")

        val photoDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?.also { if (!it.exists()) it.mkdirs() }
            ?: run {
                Toast.makeText(requireContext(), "Penyimpanan tidak tersedia", Toast.LENGTH_SHORT).show()
                isTakingPhoto = false
                return
            }

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        val photoFile = File(photoDir, "RoadSense_$timestamp.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    currentPhotoPath = photoFile.absolutePath
                    binding.ivPhotoPreview.visibility = View.VISIBLE
                    binding.ivPhotoPreview.setImageURI(Uri.fromFile(photoFile))
                    Toast.makeText(requireContext(), getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()

                    lifecycleScope.launch {
                        delay(1500L) // lebih lama untuk Xiaomi
                        try {
                            cameraDialog?.dismiss()
                        } catch (e: Exception) {
                            Timber.w(e, "Dismiss dialog error")
                        }
                    }
                    isTakingPhoto = false
                }


                override fun onError(exception: ImageCaptureException) {
                    Timber.e(exception, "Capture error: ${exception.imageCaptureError} - ${exception.message}")
                    Toast.makeText(requireContext(), "Gagal ambil foto: ${exception.message}", Toast.LENGTH_LONG).show()
                    cameraDialog?.dismiss()  // langsung tutup kalau error
                    isTakingPhoto = false
                }
            }
        )
    }

    private fun releaseCamera() {
        try {
            cameraProvider?.let { provider ->
                provider.unbindAll()
                Timber.i("unbindAll berhasil - camera seharusnya idle sekarang")
            } ?: Timber.w("releaseCamera: provider sudah null")
            cameraProvider = null
            imageCapture = null
        } catch (e: Exception) {
            Timber.w(e, "Release kamera non-fatal error (Xiaomi common)")
        }
    }

    // ========== VOICE RECORDING ==========

    @SuppressLint("NewApi")
    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(requireContext(), getString(R.string.microphone_permission_required), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
            val audioDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?.also { if (!it.exists()) it.mkdirs() }
                ?: return

            val audioFile = File(audioDir, "RoadSense_voice_$timestamp.3gp")
            audioFilePath = audioFile.absolutePath

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

            binding.btnVoice.visibility = View.GONE
            binding.layoutRecording.visibility = View.VISIBLE
            binding.btnPlayVoice.visibility = View.GONE
        } catch (e: Exception) {
            Timber.e(e, "Gagal mulai rekam suara")
            Toast.makeText(requireContext(), getString(R.string.recording_failed), Toast.LENGTH_SHORT).show()
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
                binding.tvRecordingTime.text = String.format(
                    Locale.US,
                    "%02d:%02d",
                    minutes, seconds
                )
                delay(1000)
            }
        }
    }

    private fun playRecording() {
        if (audioFilePath == null) return

        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            binding.btnPlayVoice.text = getString(R.string.play_recording)
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFilePath)
                prepare()
                start()
                setOnCompletionListener {
                    binding.btnPlayVoice.text = getString(R.string.play_recording)
                }
            }
            binding.btnPlayVoice.text = getString(R.string.stop)
        } catch (e: Exception) {
            Timber.e(e, "Gagal memutar rekaman")
            Toast.makeText(requireContext(), getString(R.string.play_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ========== SIMPAN SEGMEN ==========

    private fun saveSegment() {
        val roadName = binding.etRoadName.text?.toString()?.trim() ?: ""
        if (roadName.isEmpty()) {
            binding.etRoadName.error = getString(R.string.road_name_required)
            return
        }

        val condition = binding.actvCondition.text?.toString()?.takeIf { it.isNotBlank() }
            ?: args.conditionAuto
        val surface = binding.actvSurface.text?.toString()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.default_surface)
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
                Toast.makeText(requireContext(), getString(R.string.segment_saved), Toast.LENGTH_SHORT).show()
                setFragmentResult("segment_saved", bundleOf("session_id" to args.sessionId))
                dismiss()
            } else {
                Toast.makeText(requireContext(), getString(R.string.segment_save_failed), Toast.LENGTH_SHORT).show()
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
        releaseCamera()
        if (isRecording) stopRecording()
        mediaRecorder?.release()
        mediaPlayer?.release()
        recordingTimerJob?.cancel()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdownNow()
        }
    }
}