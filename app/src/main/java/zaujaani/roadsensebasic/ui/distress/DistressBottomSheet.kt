package zaujaani.roadsensebasic.ui.distress

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import zaujaani.roadsensebasic.BuildConfig
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.DistressType
import zaujaani.roadsensebasic.data.local.entity.Severity
import zaujaani.roadsensebasic.databinding.BottomSheetDistressBinding
import java.io.File
import java.io.IOException
import java.io.OutputStream
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
            val path = currentPhotoPath
            if (success && path != null) {
                val file = File(path)
                if (file.exists()) {
                    // Ambil data watermark
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    val distance = viewModel.getCurrentDistance().toInt()
                    val location = viewModel.getCurrentLocation()
                    val lat = location?.latitude?.let { String.format(Locale.getDefault(), "%.6f", it) } ?: "N/A"
                    val lon = location?.longitude?.let { String.format(Locale.getDefault(), "%.6f", it) } ?: "N/A"
                    val roadName = viewModel.getRoadName()

                    val textLines = buildList {
                        add(getString(R.string.watermark_app_name))
                        add(timestamp)
                        add(getString(R.string.watermark_distance, distance))
                        add(getString(R.string.watermark_coord, lat, lon))
                        if (roadName.isNotBlank()) add(roadName)
                    }

                    lifecycleScope.launch(Dispatchers.IO) {
                        val processedPath = addWatermarkToImage(path, textLines)
                        withContext(Dispatchers.Main) {
                            if (processedPath != null) {
                                // Simpan ke galeri publik
                                saveImageToPublicGallery(processedPath)
                                // Tampilkan thumbnail
                                binding.ivPhotoThumb.visibility = View.VISIBLE
                                val bitmap = BitmapFactory.decodeFile(processedPath)
                                binding.ivPhotoThumb.setImageBitmap(bitmap)
                                Toast.makeText(requireContext(), getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
                                Timber.d("Foto distress dengan watermark disimpan di: $processedPath")
                            } else {
                                Toast.makeText(requireContext(), getString(R.string.photo_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
                    currentPhotoPath = null
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.photo_failed), Toast.LENGTH_SHORT).show()
                currentPhotoPath = null
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
            else Toast.makeText(requireContext(), getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
        }

    // Voice
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording()
            else Toast.makeText(requireContext(), getString(R.string.microphone_permission_required), Toast.LENGTH_SHORT).show()
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
        setupQuickButtons()
        setupListeners()
        updateUnitLabel()

        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is SaveResult.Success -> {
                    Toast.makeText(requireContext(), getString(R.string.distress_saved), Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                is SaveResult.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
                SaveResult.Loading -> {}
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
        // Distress Type dropdown
        val distressAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            DistressType.entries.map { it.displayName }
        )
        binding.actvDistressType.setAdapter(distressAdapter)

        binding.actvDistressType.setOnItemClickListener { _, _, position, _ ->
            selectedType = DistressType.entries[position]
            updateUnitLabel()
        }

        binding.actvDistressType.setOnClickListener {
            binding.actvDistressType.showDropDown()
        }

        // Severity dropdown
        val severityAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            Severity.entries.map { it.displayName }
        )
        binding.actvSeverity.setAdapter(severityAdapter)

        binding.actvSeverity.setOnItemClickListener { _, _, position, _ ->
            selectedSeverity = Severity.entries[position]
        }

        binding.actvSeverity.setOnClickListener {
            binding.actvSeverity.showDropDown()
        }
    }

    private fun setupQuickButtons() {
        binding.btnQuick5.setOnClickListener { binding.etLengthArea.setText(getString(R.string.quick_5)) }
        binding.btnQuick10.setOnClickListener { binding.etLengthArea.setText(getString(R.string.quick_10)) }
        binding.btnQuick20.setOnClickListener { binding.etLengthArea.setText(getString(R.string.quick_20)) }
        binding.btnQuick50.setOnClickListener { binding.etLengthArea.setText(getString(R.string.quick_50)) }
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
            DistressType.CRACK -> getString(R.string.unit_length)
            DistressType.POTHole -> getString(R.string.unit_area)
            else -> getString(R.string.unit_default)
        }
        binding.tvUnitLabel.text = unit
    }

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
            Toast.makeText(requireContext(), getString(R.string.photo_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File(storageDir, "ROADSENSE_${timestamp}.jpg")
        currentPhotoPath = file.absolutePath
        return file
    }

    // Watermark dan Gallery
    private fun addWatermarkToImage(originalPath: String, textLines: List<String>): String? {
        return try {
            val originalBitmap = BitmapFactory.decodeFile(originalPath) ?: return null
            val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)

            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 40f
                isAntiAlias = true
                setShadowLayer(5f, 2f, 2f, Color.BLACK)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            var y = 80f
            for (line in textLines) {
                canvas.drawText(line, 50f, y, paint)
                y += 60f
            }

            File(originalPath).outputStream().use { out ->
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            originalBitmap.recycle()
            mutableBitmap.recycle()
            originalPath
        } catch (e: Exception) {
            Timber.e(e, "Gagal menambahkan watermark")
            null
        }
    }

    private suspend fun saveImageToPublicGallery(sourcePath: String) = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val displayName = "RoadSense_Distress_$timestamp.jpg"
            val sourceFile = File(sourcePath)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/RoadSense")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = requireContext().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out: OutputStream ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    Timber.d("Photo saved to gallery via MediaStore: $uri")
                }
            } else {
                val publicPictures = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val destDir = File(publicPictures, "RoadSense").apply { mkdirs() }
                val destFile = File(destDir, displayName)
                sourceFile.copyTo(destFile, overwrite = true)

                MediaScannerConnection.scanFile(
                    requireContext(),
                    arrayOf(destFile.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
                Timber.d("Photo saved to gallery: ${destFile.absolutePath}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Gagal menyimpan foto ke galeri")
        }
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
            binding.btnVoice.text = getString(R.string.stop)
            binding.btnVoice.setIconResource(R.drawable.ic_stop)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording")
            Toast.makeText(requireContext(), getString(R.string.recording_failed), Toast.LENGTH_SHORT).show()
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
            binding.btnVoice.text = getString(R.string.record_voice)
            binding.btnVoice.setIconResource(R.drawable.ic_mic)
            if (currentAudioFile != null) {
                Toast.makeText(requireContext(), getString(R.string.audio_saved), Toast.LENGTH_SHORT).show()
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

    private fun saveDistress() {
        val typeStr = binding.actvDistressType.text.toString()
        val severityStr = binding.actvSeverity.text.toString()
        val lengthAreaStr = binding.etLengthArea.text.toString()
        val notes = binding.etNotes.text.toString()

        if (typeStr.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.select_distress_type), Toast.LENGTH_SHORT).show()
            return
        }
        if (severityStr.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.select_severity), Toast.LENGTH_SHORT).show()
            return
        }
        if (lengthAreaStr.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.enter_length_area), Toast.LENGTH_SHORT).show()
            return
        }

        val type = DistressType.entries.find { it.displayName == typeStr }
        val severity = Severity.entries.find { it.displayName == severityStr }
        val lengthArea = lengthAreaStr.toDoubleOrNull()

        if (type == null || severity == null || lengthArea == null) {
            Toast.makeText(requireContext(), getString(R.string.invalid_data), Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.saveDistress(
            type = type,
            severity = severity,
            lengthOrArea = lengthArea,
            photoPath = currentPhotoPath ?: "",
            audioPath = currentAudioFile?.absolutePath ?: "",
            notes = notes
        )
    }
}