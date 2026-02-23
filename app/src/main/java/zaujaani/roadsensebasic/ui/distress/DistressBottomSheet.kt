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
import zaujaani.roadsensebasic.data.local.entity.DistressType
import zaujaani.roadsensebasic.data.local.entity.Severity
import zaujaani.roadsensebasic.data.local.entity.SurveyMode
import zaujaani.roadsensebasic.databinding.BottomSheetDistressBinding
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DistressBottomSheet — Input kerusakan untuk mode SDI (Bina Marga PD T-05-2005-B).
 *
 * PENTING:
 *   - Bottom sheet ini KHUSUS untuk mode SDI.
 *   - Untuk mode PCI, gunakan [PciDistressBottomSheet].
 *   - Foto WAJIB diambil, diberi watermark otomatis, dan disimpan ke galeri HP.
 *
 * FIX yang diterapkan:
 *   #1 Pemisahan SDI / PCI: kelas ini tidak lagi menangani PCIDistressType
 *   #4 Watermark: ditambahkan label "Mode: SDI" agar dokumentasi jelas
 *   #5 Gallery: foto tersimpan ke Pictures/RoadSense di galeri HP
 */
@AndroidEntryPoint
class DistressBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDistressBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DistressViewModel by viewModels()

    // ── State ─────────────────────────────────────────────────────────────
    private var selectedType: DistressType? = null
    private var selectedSeverity: Severity? = null
    private var selectedPresetValue: Double? = null  // nilai dari dropdown preset

    private var currentPhotoPath: String? = null
    private var currentAudioFile: File? = null

    private var isRecording       = false
    private var mediaRecorder: MediaRecorder? = null
    private var recordingTimerJob: Job? = null
    private var recordingSeconds  = 0

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
                val typeLabel = selectedType?.displayName ?: ""

                // FIX #4: Watermark mencantumkan Mode: SDI secara eksplisit
                val textLines = buildList {
                    add("RoadSense | Mode: SDI")
                    add(timestamp)
                    add("STA: $distance m  |  $typeLabel")
                    add("GPS: $lat, $lon")
                    if (roadName.isNotBlank()) add("Ruas: $roadName")
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    val processed = addWatermarkToImage(path, textLines)
                    withContext(Dispatchers.Main) {
                        if (processed != null) {
                            // FIX #5: Simpan ke galeri HP (wajib muncul di galeri)
                            lifecycleScope.launch(Dispatchers.IO) {
                                saveImageToPublicGallery(processed)
                            }
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

        // FIX #1: Pastikan mode aktif adalah SDI; tolak jika bukan
        val mode = viewModel.getSurveyMode()
        if (mode != SurveyMode.SDI) {
            showToast("DistressBottomSheet hanya untuk mode SDI")
            dismiss()
            return
        }

        setupDropdowns()
        setupListeners()
        // Preset dropdown dimuat ulang saat jenis kerusakan dipilih (refreshQuantityPreset)
        // Tidak perlu inisialisasi preset di sini karena belum ada jenis yang dipilih.

        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is SaveResult.Success -> {
                    showToast(getString(R.string.distress_saved))
                    dismiss()
                }
                is SaveResult.Error   -> {
                    showToast(result.message)
                    binding.btnSave.isEnabled = true
                }
                SaveResult.Loading    -> binding.btnSave.isEnabled = false
            }
        }
    }

    // ── Dropdowns ─────────────────────────────────────────────────────────

    private fun setupDropdowns() {
        // FIX #1: HANYA jenis kerusakan SDI (DistressType — Bina Marga)
        val typeAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            DistressType.entries.map { it.displayName }
        )
        binding.actvDistressType.setAdapter(typeAdapter)
        binding.actvDistressType.setOnItemClickListener { _, _, position, _ ->
            selectedType = DistressType.entries[position]
            selectedPresetValue = null  // reset preset lama saat ganti jenis
            onDistressTypeSelected(selectedType!!)
        }

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

    private fun onDistressTypeSelected(type: DistressType) {
        binding.tvUnitLabel.text = type.unit
        binding.tvSurveyorGuide.text = type.getSurveyorGuide()
        binding.tvSurveyorGuide.visibility = View.VISIBLE
        // Reset pilihan sebelumnya
        binding.actvQuantityPreset.setText("", false)
        binding.etCustomQuantity.text?.clear()
        refreshQuantityPreset()
    }

    /**
     * Isi ulang dropdown preset sesuai jenis kerusakan yang dipilih.
     * Label preset mencantumkan satuan agar surveyor tidak perlu melihat chip satuan.
     * Contoh SDI: "5 m", "10 m", "0.25 m²"
     */
    private fun refreshQuantityPreset() {
        val type = selectedType
        val unit = type?.unit ?: ""
        val presets = type?.getQuickPresets() ?: listOf(
            "5" to 5.0, "10" to 10.0, "25" to 25.0, "50" to 50.0
        )

        // Label dropdown: "0.25 m²", "0.50 m²", dst — termasuk satuan agar jelas
        val labels = presets.map { (label, _) ->
            if (unit.isNotBlank() && !label.contains(unit)) "$label $unit" else label
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        binding.actvQuantityPreset.setAdapter(adapter)

        binding.actvQuantityPreset.setOnItemClickListener { _, _, position, _ ->
            val value = presets.getOrNull(position)?.second ?: return@setOnItemClickListener
            // Saat preset dipilih → kosongkan custom field agar tidak konflik
            binding.etCustomQuantity.text?.clear()
            selectedPresetValue = value
            // Tampilkan konfirmasi singkat di hint custom field
            binding.tilCustomQuantity.hint = "Terpilih: ${presets[position].first} $unit  (atau isi nilai lain)"
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
        val file = File(dir, "ROADSENSE_SDI_${ts}.jpg")
        currentPhotoPath = file.absolutePath
        return file
    }

    // ── Voice Recording ───────────────────────────────────────────────────

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
            val audioFile = File(audioDir, "ROADSENSE_SDI_AUDIO_$ts.mp4")
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
            recordingSeconds = 0
            startRecordingTimer()
            updateVoiceButtonState()
            showToast("⏺ Merekam... (maks ${MAX_RECORDING_SECONDS} dtk)")

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
            recordingSeconds = 0
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
                recordingSeconds++
                if (_binding != null) {
                    val remaining = MAX_RECORDING_SECONDS - recordingSeconds
                    binding.btnVoice.text = "⏹ Stop ($remaining dtk)"
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

    // ── Save Distress ─────────────────────────────────────────────────────

    private fun saveDistress() {
        val typeStr     = binding.actvDistressType.text.toString().trim()
        val severityStr = binding.actvSeverity.text.toString().trim()
        val notes       = binding.etNotes.text?.toString()?.trim() ?: ""

        if (typeStr.isBlank()) {
            showToast(getString(R.string.select_distress_type)); return
        }
        if (severityStr.isBlank()) {
            showToast(getString(R.string.select_severity)); return
        }

        val type     = DistressType.entries.find { it.displayName == typeStr }
        val severity = Severity.entries.find { it.displayName == severityStr }

        if (type == null || severity == null) {
            showToast(getString(R.string.invalid_data)); return
        }

        // Prioritas: custom field → preset dropdown
        val customStr = binding.etCustomQuantity.text?.toString()?.trim() ?: ""
        val quantity: Double? = if (customStr.isNotBlank()) {
            customStr.toDoubleOrNull()
        } else {
            selectedPresetValue
        }

        if (quantity == null || quantity <= 0) {
            showToast("Pilih atau isi nilai panjang/luas terlebih dahulu"); return
        }

        if (isRecording) stopRecording()
        binding.btnSave.isEnabled = false

        viewModel.saveDistress(
            type         = type,
            severity     = severity,
            lengthOrArea = quantity,
            photoPath    = currentPhotoPath ?: "",
            audioPath    = currentAudioFile?.absolutePath ?: "",
            notes        = notes
        )
    }

    // ── Watermark ─────────────────────────────────────────────────────────

    /**
     * Tambahkan watermark ke foto.
     * FIX #4: Baris pertama selalu "RoadSense | Mode: SDI" agar jelas.
     */
    private fun addWatermarkToImage(originalPath: String, textLines: List<String>): String? {
        return try {
            val original = BitmapFactory.decodeFile(originalPath) ?: return null
            val mutable  = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas   = Canvas(mutable)

            val lineCount = textLines.size
            val lineHeight = 56f
            val bgHeight = lineCount * lineHeight + 24f

            val bgPaint = Paint().apply {
                color = Color.argb(160, 0, 0, 0)
            }
            canvas.drawRect(0f, 0f, mutable.width.toFloat(), bgHeight, bgPaint)

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
                y += lineHeight
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

    // ── Gallery ───────────────────────────────────────────────────────────

    /**
     * FIX #5: Simpan foto ke galeri publik agar muncul di aplikasi Galeri HP.
     * Support API 29+ (MediaStore) dan API < 29 (file copy + MediaScanner).
     */
    private suspend fun saveImageToPublicGallery(sourcePath: String) = withContext(Dispatchers.IO) {
        try {
            val ts          = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val displayName = "RoadSense_SDI_$ts.jpg"
            val sourceFile  = File(sourcePath)
            val context     = requireContext()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/RoadSense")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri      = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { out: OutputStream ->
                        sourceFile.inputStream().copyTo(out)
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                }
            } else {
                val pubDir  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val destDir = File(pubDir, "RoadSense").apply { mkdirs() }
                val dest    = File(destDir, displayName)
                sourceFile.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(dest.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
            }
            Timber.d("Foto SDI disimpan ke galeri: $displayName")
        } catch (e: Exception) {
            Timber.e(e, "Gagal menyimpan foto SDI ke galeri")
        }
    }

    private fun showToast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        if (isRecording) stopRecording()
        mediaRecorder?.release()
        recordingTimerJob?.cancel()
        _binding = null
    }
}