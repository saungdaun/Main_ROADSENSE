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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import zaujaani.roadsensebasic.BuildConfig
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.PCIDistressType
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
 * PciDistressBottomSheet — Input kerusakan untuk mode PCI (ASTM D6433).
 *
 * PENTING:
 *   - Bottom sheet ini KHUSUS untuk mode PCI.
 *   - Untuk mode SDI, gunakan [DistressBottomSheet].
 *   - Foto WAJIB diambil, diberi watermark otomatis, dan disimpan ke galeri HP.
 *
 * FIX yang diterapkan:
 *   #1 Pemisahan SDI / PCI: kelas ini hanya menangani PCIDistressType (19 jenis ASTM D6433)
 *   #4 Watermark: inline tanpa bergantung MediaManager agar watermark selalu ada,
 *      dengan label "Mode: PCI | ASTM D6433" yang jelas
 *   #5 Gallery: foto tersimpan ke Pictures/RoadSense di galeri HP
 */
@AndroidEntryPoint
class PciDistressBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDistressBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DistressViewModel by viewModels()

    // ── State ─────────────────────────────────────────────────────────────
    private var selectedType: PCIDistressType? = null
    private var selectedSeverity: Severity? = null
    private var selectedPresetValue: Double? = null  // nilai dari dropdown preset
    private var currentPhotoPath: String? = null
    private var currentAudioPath: String? = null

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

                // FIX #4: Watermark dengan label mode PCI eksplisit
                val textLines = buildList {
                    add("RoadSense | Mode: PCI  (ASTM D6433)")
                    add(timestamp)
                    add("STA: $distance m  |  $typeLabel")
                    add("GPS: $lat, $lon")
                    if (roadName.isNotBlank()) add("Ruas: $roadName")
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    val processed = addWatermarkToImage(path, textLines)
                    withContext(Dispatchers.Main) {
                        if (processed != null) {
                            // FIX #5: Simpan ke galeri HP
                            lifecycleScope.launch(Dispatchers.IO) {
                                saveImageToPublicGallery(processed)
                            }
                            binding.ivPhotoThumb.visibility = View.VISIBLE
                            BitmapFactory.decodeFile(processed)?.let { bmp ->
                                binding.ivPhotoThumb.setImageBitmap(bmp)
                            }
                            Toast.makeText(requireContext(), R.string.photo_saved, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), R.string.photo_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                Toast.makeText(requireContext(), R.string.photo_failed, Toast.LENGTH_SHORT).show()
                currentPhotoPath = null
            }
        }

    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
            else Toast.makeText(requireContext(), R.string.camera_permission_required, Toast.LENGTH_SHORT).show()
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

        // FIX #1: Pastikan mode aktif adalah PCI; tolak jika bukan
        val mode = viewModel.getSurveyMode()
        if (mode != SurveyMode.PCI) {
            Toast.makeText(requireContext(), "PciDistressBottomSheet hanya untuk mode PCI", Toast.LENGTH_SHORT).show()
            dismiss()
            return
        }

        // Sembunyikan tombol voice — PCI survey tidak menggunakan voice note per item
        // (voice note tersedia di MapFragment level, bukan per distress item)
        binding.btnVoice.visibility = View.GONE

        setupDropdowns()
        setupListeners()
        // Preset dropdown dimuat saat jenis kerusakan dipilih (refreshQuantityPreset)

        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is SaveResult.Success -> {
                    Toast.makeText(requireContext(), R.string.distress_saved, Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                is SaveResult.Error   -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    binding.btnSave.isEnabled = true
                }
                SaveResult.Loading    -> binding.btnSave.isEnabled = false
            }
        }
    }

    // ── Dropdowns ─────────────────────────────────────────────────────────

    private fun setupDropdowns() {
        // FIX #1: HANYA jenis kerusakan PCI (19 jenis ASTM D6433)
        val typeAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            PCIDistressType.entries.map { it.displayName }
        )
        binding.actvDistressType.setAdapter(typeAdapter)
        binding.actvDistressType.setOnItemClickListener { _, _, position, _ ->
            selectedType = PCIDistressType.entries[position]
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

    private fun onDistressTypeSelected(type: PCIDistressType) {
        binding.tvUnitLabel.text = type.unitLabel
        binding.tvSurveyorGuide.text = type.getSurveyorGuide()
        binding.tvSurveyorGuide.visibility = View.VISIBLE
        // Reset pilihan sebelumnya
        binding.actvQuantityPreset.setText("", false)
        binding.etCustomQuantity.text?.clear()
        selectedPresetValue = null
        refreshQuantityPreset()
    }

    /**
     * Isi ulang dropdown preset sesuai jenis kerusakan PCI yang dipilih.
     *
     * PCI per 50m → presets relatif lebih kecil dari SDI (per 100m).
     * Label mencantumkan satuan eksplisit agar surveyor tidak perlu baca chip.
     * Contoh: "1 m²", "5 m²", "10 lubang"
     */
    private fun refreshQuantityPreset() {
        val type = selectedType
        val unit = type?.unitLabel ?: ""
        val presets = type?.getQuickPresets() ?: listOf(
            "1" to 1.0, "5" to 5.0, "10" to 10.0, "20" to 20.0
        )

        val labels = presets.map { (label, _) ->
            if (unit.isNotBlank() && !label.contains(unit)) "$label $unit" else label
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        binding.actvQuantityPreset.setAdapter(adapter)

        binding.actvQuantityPreset.setOnItemClickListener { _, _, position, _ ->
            val value = presets.getOrNull(position)?.second ?: return@setOnItemClickListener
            binding.etCustomQuantity.text?.clear()
            selectedPresetValue = value
            binding.tilCustomQuantity.hint = "Terpilih: ${presets[position].first} $unit  (atau isi nilai lain)"
        }
    }

    private fun setupListeners() {
        binding.btnAddPhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnSave.setOnClickListener { saveDistress() }
    }

    // ── Camera ────────────────────────────────────────────────────────────

    private fun launchCamera() {
        try {
            val ts       = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir      = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val file     = File(dir, "ROADSENSE_PCI_$ts.jpg")
            currentPhotoPath = file.absolutePath
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
            takePictureLauncher.launch(uri)
        } catch (e: IOException) {
            Timber.e(e, "Error creating PCI photo file")
            Toast.makeText(requireContext(), R.string.photo_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Save Distress ─────────────────────────────────────────────────────

    private fun saveDistress() {
        val type     = selectedType
        val severity = selectedSeverity
        val notes    = binding.etNotes.text.toString().trim()

        if (type == null) {
            Toast.makeText(requireContext(), R.string.select_distress_type, Toast.LENGTH_SHORT).show()
            return
        }
        if (severity == null) {
            Toast.makeText(requireContext(), R.string.select_severity, Toast.LENGTH_SHORT).show()
            return
        }

        // Prioritas: custom field → preset dropdown
        val customStr = binding.etCustomQuantity.text?.toString()?.trim() ?: ""
        val quantity: Double? = if (customStr.isNotBlank()) {
            customStr.toDoubleOrNull()
        } else {
            selectedPresetValue
        }

        if (quantity == null || quantity <= 0) {
            Toast.makeText(requireContext(), "Pilih atau isi nilai terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSave.isEnabled = false

        viewModel.savePciDistress(
            type      = type,
            severity  = severity,
            quantity  = quantity,
            photoPath = currentPhotoPath ?: "",
            audioPath = currentAudioPath ?: "",
            notes     = notes
        )
    }

    // ── Watermark ─────────────────────────────────────────────────────────

    /**
     * FIX #4: Tambahkan watermark ke foto PCI.
     * Label mode "PCI | ASTM D6433" selalu ada di baris pertama.
     */
    private fun addWatermarkToImage(originalPath: String, textLines: List<String>): String? {
        return try {
            val original = BitmapFactory.decodeFile(originalPath) ?: return null
            val mutable  = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas   = Canvas(mutable)

            val lineHeight = 56f
            val bgHeight   = textLines.size * lineHeight + 24f

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
            Timber.e(e, "Gagal menambahkan watermark PCI")
            null
        }
    }

    // ── Gallery ───────────────────────────────────────────────────────────

    /**
     * FIX #5: Simpan foto ke galeri publik agar muncul di aplikasi Galeri HP.
     * Nama file mengandung prefix "PCI" agar mudah diidentifikasi.
     */
    private suspend fun saveImageToPublicGallery(sourcePath: String) = withContext(Dispatchers.IO) {
        try {
            val ts          = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val displayName = "RoadSense_PCI_$ts.jpg"
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
            Timber.d("Foto PCI disimpan ke galeri: $displayName")
        } catch (e: Exception) {
            Timber.e(e, "Gagal menyimpan foto PCI ke galeri")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}