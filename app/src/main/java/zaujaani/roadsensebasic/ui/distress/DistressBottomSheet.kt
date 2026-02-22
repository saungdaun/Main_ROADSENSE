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
import zaujaani.roadsensebasic.data.local.entity.*
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

    // ── State ─────────────────────────────────────────────────────────────
    private var selectedSdiType: DistressType? = null
    private var selectedPciType: PCIDistressType? = null
    private var selectedSeverity: Severity? = null
    private var currentPhotoPath: String? = null
    private var currentAudioFile: File? = null

    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var recordingTimerJob: Job? = null
    private var _recordingSeconds = 0

    companion object {
        private const val MAX_RECORDING_SECONDS = 30
    }

    // ── Camera launcher ───────────────────────────────────────────────────
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val path = currentPhotoPath
            if (success && path != null && File(path).exists()) {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val distance  = viewModel.getCurrentDistance().toInt()
                val location  = viewModel.getCurrentLocation()
                val lat = location?.latitude?.let  { String.format(Locale.US, "%.6f", it) } ?: "N/A"
                val lon = location?.longitude?.let { String.format(Locale.US, "%.6f", it) } ?: "N/A"
                val roadName  = viewModel.getRoadName()
                val mode      = viewModel.getSurveyMode()
                val typeLabel = if (mode == SurveyMode.PCI) {
                    selectedPciType?.displayName ?: ""
                } else {
                    selectedSdiType?.displayName ?: ""
                }

                val textLines = buildList {
                    add("RoadSense Survey")
                    add(timestamp)
                    add("STA: $distance m  |  $typeLabel")
                    add("GPS: $lat, $lon")
                    if (roadName.isNotBlank()) add("Ruas: $roadName")
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    val processed = addWatermarkToImage(path, textLines)
                    withContext(Dispatchers.Main) {
                        if (processed != null) {
                            lifecycleScope.launch(Dispatchers.IO) { saveImageToPublicGallery(processed) }
                            binding.ivPhotoThumb.visibility = View.VISIBLE
                            BitmapFactory.decodeFile(processed)?.let { bmp ->
                                binding.ivPhotoThumb.setImageBitmap(bmp)
                            }
                            showToast(getString(R.string.photo_saved))
                        } else {
                            showToast(getString(R.string.photo_failed))
                        }
                    }
                }
            } else {
                showToast(getString(R.string.photo_failed))
                currentPhotoPath = null
            }
        }

    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
            else showToast(getString(R.string.camera_permission_required))
        }

    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording()
            else showToast(getString(R.string.microphone_permission_required))
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────

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
        setupListeners()
        refreshQuickButtons()   // preset default sebelum tipe dipilih

        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is SaveResult.Success -> {
                    showToast(getString(R.string.distress_saved))
                    dismiss()
                }
                is SaveResult.Error -> showToast(result.message)
                SaveResult.Loading  -> { /* progress sudah ada di tombol disable */ }
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

    // ── Dropdown setup ────────────────────────────────────────────────────

    private fun setupDropdowns() {
        val mode = viewModel.getSurveyMode()

        // Jenis Kerusakan (berdasarkan mode)
        if (mode == SurveyMode.PCI) {
            val pciTypeAdapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                PCIDistressType.entries.map { it.displayName }
            )
            binding.actvDistressType.setAdapter(pciTypeAdapter)
            binding.actvDistressType.setOnClickListener { binding.actvDistressType.showDropDown() }
            binding.actvDistressType.setOnItemClickListener { _, _, position, _ ->
                selectedPciType = PCIDistressType.entries[position]
                selectedSdiType = null
                onDistressTypeSelected()
            }
        } else {
            val sdiTypeAdapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                DistressType.entries.map { it.displayName }
            )
            binding.actvDistressType.setAdapter(sdiTypeAdapter)
            binding.actvDistressType.setOnClickListener { binding.actvDistressType.showDropDown() }
            binding.actvDistressType.setOnItemClickListener { _, _, position, _ ->
                selectedSdiType = DistressType.entries[position]
                selectedPciType = null
                onDistressTypeSelected()
            }
        }

        // Tingkat Keparahan
        val severityAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            Severity.entries.map { it.displayName }
        )
        binding.actvSeverity.setAdapter(severityAdapter)
        binding.actvSeverity.setOnClickListener { binding.actvSeverity.showDropDown() }
        binding.actvSeverity.setOnItemClickListener { _, _, position, _ ->
            selectedSeverity = Severity.entries[position]
        }
    }

    /**
     * Dipanggil saat jenis kerusakan dipilih dari dropdown.
     * Update: satuan, panduan surveyor, preset tombol cepat.
     */
    private fun onDistressTypeSelected() {
        val mode = viewModel.getSurveyMode()
        if (mode == SurveyMode.PCI) {
            val type = selectedPciType
            if (type != null) {
                binding.tvUnitLabel.text = type.unitLabel
                binding.tvSurveyorGuide.text = type.getSurveyorGuide()
                binding.tvSurveyorGuide.visibility = View.VISIBLE
                binding.etLengthArea.text?.clear()
                refreshQuickButtons()
            }
        } else {
            val type = selectedSdiType
            if (type != null) {
                binding.tvUnitLabel.text = type.unit
                binding.tvSurveyorGuide.text = type.getSurveyorGuide()
                binding.tvSurveyorGuide.visibility = View.VISIBLE
                binding.etLengthArea.text?.clear()
                refreshQuickButtons()
            }
        }
    }

    /**
     * Update label & nilai 4 tombol preset cepat.
     */
    private fun refreshQuickButtons() {
        val mode = viewModel.getSurveyMode()
        val presets: List<Pair<String, Double>> = if (mode == SurveyMode.PCI) {
            selectedPciType?.getQuickPresets()
                ?: listOf("5" to 5.0, "10" to 10.0, "20" to 20.0, "50" to 50.0)
        } else {
            selectedSdiType?.getQuickPresets()
                ?: listOf("5m" to 5.0, "10m" to 10.0, "20m" to 20.0, "50m" to 50.0)
        }

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
        binding.btnAddPhoto.setOnClickListener { openCamera() }
        binding.btnVoice.setOnClickListener   { toggleVoiceRecording() }
        binding.btnSave.setOnClickListener    { saveDistress() }
    }

    // ── Camera ────────────────────────────────────────────────────────────

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) launchCamera()
        else cameraPermLauncher.launch(Manifest.permission.CAMERA)
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
            showToast(getString(R.string.photo_failed))
        }
    }

    private fun createImageFile(): File {
        val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir  = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File(dir, "ROADSENSE_${ts}.jpg")
        currentPhotoPath = file.absolutePath
        return file
    }

    // ── Voice: toggle start/stop ──────────────────────────────────────────

    private fun toggleVoiceRecording() {
        if (isRecording) stopRecording()
        else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) startRecording()
            else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        try {
            val ts        = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val audioDir  = requireContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val audioFile = File(audioDir, "ROADSENSE_AUDIO_$ts.mp4")
            currentAudioFile = audioFile

            mediaRecorder = (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        MediaRecorder(requireContext())
                    else
                        @Suppress("DEPRECATION") MediaRecorder()
                    ).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(128000)
                    setMaxDuration(MAX_RECORDING_SECONDS * 1000)
                    setOutputFile(audioFile.absolutePath)
                    setOnInfoListener { _, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                            stopRecording()
                        }
                    }
                    prepare()
                    start()
                }

            isRecording      = true
            _recordingSeconds = 0
            startRecordingTimer()
            updateVoiceButtonState()
            showToast("⏺ Merekam... (maks ${MAX_RECORDING_SECONDS}d)")

        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording")
            showToast(getString(R.string.recording_failed))
            mediaRecorder?.release()
            mediaRecorder    = null
            currentAudioFile = null
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply { stop(); reset(); release() }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping recording")
        } finally {
            mediaRecorder    = null
            isRecording      = false
            _recordingSeconds = 0
            recordingTimerJob?.cancel()
            updateVoiceButtonState()

            val file = currentAudioFile
            if (file != null && file.exists() && file.length() > 0) {
                showToast(getString(R.string.audio_saved))
            } else {
                currentAudioFile = null
                showToast("File rekaman kosong, coba lagi")
            }
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = lifecycleScope.launch {
            while (isRecording) {
                delay(1000L)
                _recordingSeconds++
                if (_binding != null) {
                    val remaining = MAX_RECORDING_SECONDS - _recordingSeconds
                    binding.btnVoice.text = "⏹ Stop ($remaining d)"
                }
            }
        }
    }

    private fun updateVoiceButtonState() {
        if (_binding == null) return
        if (isRecording) {
            binding.btnVoice.text = getString(R.string.stop)
            binding.btnVoice.setIconResource(R.drawable.ic_stop)
        } else {
            binding.btnVoice.text = getString(R.string.record_voice)
            binding.btnVoice.setIconResource(R.drawable.ic_mic)
        }
    }

    // ── Simpan distress ───────────────────────────────────────────────────

    private fun saveDistress() {
        val typeStr       = binding.actvDistressType.text.toString().trim()
        val severityStr   = binding.actvSeverity.text.toString().trim()
        val quantityStr   = binding.etLengthArea.text.toString().trim()
        val notes         = binding.etNotes.text?.toString()?.trim() ?: ""

        if (typeStr.isBlank()) {
            showToast(getString(R.string.select_distress_type)); return
        }
        if (severityStr.isBlank()) {
            showToast(getString(R.string.select_severity)); return
        }
        if (quantityStr.isBlank()) {
            showToast(getString(R.string.enter_length_area)); return
        }

        val severity = Severity.entries.find { it.displayName == severityStr }
        val quantity = quantityStr.toDoubleOrNull()

        if (severity == null || quantity == null || quantity <= 0) {
            showToast(getString(R.string.invalid_data)); return
        }

        // Pastikan recording berhenti sebelum simpan
        if (isRecording) stopRecording()

        binding.btnSave.isEnabled = false

        val mode = viewModel.getSurveyMode()
        if (mode == SurveyMode.PCI) {
            val type = PCIDistressType.entries.find { it.displayName == typeStr }
            if (type == null) {
                showToast("Jenis kerusakan tidak valid")
                binding.btnSave.isEnabled = true
                return
            }
            viewModel.savePciDistress(
                type       = type,
                severity   = severity,
                quantity   = quantity,
                photoPath  = currentPhotoPath ?: "",
                audioPath  = currentAudioFile?.absolutePath ?: "",
                notes      = notes
            )
        } else {
            val type = DistressType.entries.find { it.displayName == typeStr }
            if (type == null) {
                showToast("Jenis kerusakan tidak valid")
                binding.btnSave.isEnabled = true
                return
            }
            viewModel.saveDistress(
                type         = type,
                severity     = severity,
                lengthOrArea = quantity,
                photoPath    = currentPhotoPath ?: "",
                audioPath    = currentAudioFile?.absolutePath ?: "",
                notes        = notes
            )
        }
    }

    // ── Watermark foto ────────────────────────────────────────────────────

    private fun addWatermarkToImage(originalPath: String, textLines: List<String>): String? {
        return try {
            val original = BitmapFactory.decodeFile(originalPath) ?: return null
            val mutable  = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas   = Canvas(mutable)

            val bgPaint = Paint().apply {
                color = Color.argb(150, 0, 0, 0)
            }
            canvas.drawRect(0f, 0f, mutable.width.toFloat(), textLines.size * 56f + 24f, bgPaint)

            val paint = Paint().apply {
                color       = Color.WHITE
                textSize    = 38f
                isAntiAlias = true
                typeface    = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }

            var y = 52f
            textLines.forEach { line ->
                canvas.drawText(line, 24f, y, paint)
                y += 56f
            }

            File(originalPath).outputStream().use { out ->
                mutable.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            original.recycle()
            mutable.recycle()
            originalPath

        } catch (e: Exception) {
            Timber.e(e, "Gagal menambahkan watermark")
            null
        }
    }

    // ── Simpan ke galeri publik ───────────────────────────────────────────

    private suspend fun saveImageToPublicGallery(sourcePath: String) = withContext(Dispatchers.IO) {
        try {
            val ts          = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val displayName = "RoadSense_$ts.jpg"
            val sourceFile  = File(sourcePath)

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
                uri?.let {
                    resolver.openOutputStream(it)?.use { out: OutputStream ->
                        sourceFile.inputStream().copyTo(out)
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                }
            } else {
                val pubDir  = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val destDir = File(pubDir, "RoadSense").apply { mkdirs() }
                val dest    = File(destDir, displayName)
                sourceFile.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(
                    requireContext(),
                    arrayOf(dest.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Gagal menyimpan foto ke galeri")
        }
    }

    private fun showToast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}