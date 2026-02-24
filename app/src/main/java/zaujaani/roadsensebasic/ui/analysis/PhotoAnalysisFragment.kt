package zaujaani.roadsensebasic.ui.analysis

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.AnalysisStatus
import zaujaani.roadsensebasic.data.local.entity.PhotoAnalysisResult
import zaujaani.roadsensebasic.databinding.FragmentPhotoAnalysisBinding
import zaujaani.roadsensebasic.ui.summary.PhotoItem
import java.io.File

/**
 * PhotoAnalysisFragment — Halaman analisis foto post-survey.
 *
 * Alur:
 * 1. Surveyor selesai survey → buka Summary → pilih session → "Analisis Foto"
 * 2. Fragment ini menampilkan grid semua foto dari session itu
 * 3. Tap satu foto → analisis tunggal + tampilkan hasil
 * 4. Tap "Analisis Semua" → batch mode dengan progress bar
 * 5. Hasil tersimpan di DB, bisa di-override manual
 *
 * Navigation args: sessionId (Long), roadName (String), mode dikirim via Bundle.
 */
@AndroidEntryPoint
class PhotoAnalysisFragment : Fragment() {

    private var _binding: FragmentPhotoAnalysisBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotoAnalysisViewModel by viewModels()

    private lateinit var adapter: PhotoAnalysisAdapter

    // Diterima dari SummaryFragment via Bundle
    private var sessionId: Long  = -1L
    private var roadName: String = ""
    private var sessionMode: String = ""
    private var photoItems: List<PhotoItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Ambil args dari bundle
        sessionId   = arguments?.getLong("sessionId", -1L) ?: -1L
        roadName    = arguments?.getString("roadName", "Tidak ada nama jalan") ?: ""
        sessionMode = arguments?.getString("sessionMode", "GENERAL") ?: "GENERAL"

        @Suppress("UNCHECKED_CAST")
        photoItems = arguments?.getParcelableArrayList<PhotoItem>("photoItems") ?: emptyList()

        setupHeader()
        setupRecyclerView()
        setupButtons()
        setupObservers()

        viewModel.loadSession(photoItems, sessionId)
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private fun setupHeader() {
        binding.tvRoadName.text = roadName.ifBlank { "Sesi #$sessionId" }
        binding.tvPhotoCount.text = "${photoItems.size} foto tersedia"
        binding.tvMode.text = when (sessionMode) {
            "SDI" -> "Survei SDI"
            "PCI" -> "Survei PCI"
            else  -> "Survei Umum"
        }
    }

    private fun setupRecyclerView() {
        adapter = PhotoAnalysisAdapter(
            onItemClick = { item, result -> showPhotoDetail(item, result) },
            onAnalyze   = { item -> viewModel.analyzeSingle(item) }
        )
        binding.recyclerViewPhotos.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewPhotos.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnAnalyzeAll.setOnClickListener {
            if (viewModel.isBatchRunning.value == true) {
                viewModel.cancelBatch()
            } else {
                viewModel.analyzeAll()
            }
        }

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupObservers() {
        // Gabungkan photos + results → update adapter
        viewModel.photos.observe(viewLifecycleOwner) { photos ->
            updateAdapter(photos, viewModel.analysisResults.value ?: emptyList())
        }
        viewModel.analysisResults.observe(viewLifecycleOwner) { results ->
            updateAdapter(viewModel.photos.value ?: emptyList(), results)
            updateSummaryChips(results)
        }

        // Batch progress
        viewModel.batchProgress.observe(viewLifecycleOwner) { progress ->
            if (progress != null && !progress.isDone) {
                binding.progressBatch.isVisible   = true
                binding.tvBatchStatus.isVisible   = true
                binding.progressBatch.progress    = progress.percent
                binding.tvBatchStatus.text =
                    "Menganalisis ${progress.current + 1}/${progress.total}: ${progress.currentPhotoLabel}"
            } else {
                binding.progressBatch.isVisible = false
                binding.tvBatchStatus.isVisible = false
            }
        }

        // Batch running → ubah teks tombol
        viewModel.isBatchRunning.observe(viewLifecycleOwner) { running ->
            binding.btnAnalyzeAll.text = if (running) "⏹ Hentikan" else "🔍 Analisis Semua"
        }

        // Error
        viewModel.errorEvent.observe(viewLifecycleOwner) { error ->
            error ?: return@observe
            Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
            viewModel.clearError()
        }

        // Single analyzing indicator
        viewModel.singleAnalyzing.observe(viewLifecycleOwner) { path ->
            adapter.setAnalyzingPath(path)
        }
    }

    // ── Adapter update ────────────────────────────────────────────────────

    private fun updateAdapter(
        photos: List<PhotoItem>,
        results: List<PhotoAnalysisResult>
    ) {
        val resultMap = results.associateBy { it.photoPath }
        val items = photos.map { photo ->
            photo to resultMap[photo.path]
        }
        adapter.submitList(items)
        binding.tvPhotoCount.text = "${photos.size} foto  •  ${results.count { it.status == AnalysisStatus.DONE }} dianalisis"
    }

    private fun updateSummaryChips(results: List<PhotoAnalysisResult>) {
        val done = results.count { it.status == AnalysisStatus.DONE }
        val pending = results.count { it.status == AnalysisStatus.PENDING }
        val failed = results.count { it.status == AnalysisStatus.FAILED }
        val invalid = results.count { it.status == AnalysisStatus.SKIPPED }

        binding.chipDone.text    = "✓ $done Selesai"
        binding.chipPending.text = "⏳ $pending Belum"
        binding.chipFailed.text  = "❌ $failed Gagal"
        binding.chipInvalid.text = "⊘ $invalid Bukan Jalan"

        binding.chipFailed.isVisible  = failed > 0
        binding.chipInvalid.isVisible = invalid > 0
    }

    // ── Photo detail dialog ───────────────────────────────────────────────

    private fun showPhotoDetail(item: PhotoItem, result: PhotoAnalysisResult?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_photo_analysis_detail, null)

        // Preview foto
        val imageView = dialogView.findViewById<ImageView>(R.id.ivPhoto)
        val file = File(item.path)
        if (file.exists()) {
            val opts   = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bitmap = BitmapFactory.decodeFile(item.path, opts)
            if (bitmap != null) imageView.setImageBitmap(bitmap)
        }

        // Metadata foto
        dialogView.findViewById<TextView>(R.id.tvPhotoMeta).text = buildString {
            append(item.caption(0, 1).lines().take(2).joinToString("\n"))
        }

        // Hasil analisis
        val tvResult    = dialogView.findViewById<TextView>(R.id.tvAnalysisResult)
        val tvConfidence = dialogView.findViewById<TextView>(R.id.tvConfidence)
        val tvDesc      = dialogView.findViewById<TextView>(R.id.tvDescription)
        val tvReco      = dialogView.findViewById<TextView>(R.id.tvRecommendation)
        val progressConf = dialogView.findViewById<LinearProgressIndicator>(R.id.progressConfidence)
        val tvStatus    = dialogView.findViewById<TextView>(R.id.tvAnalysisStatus)

        when (result?.status) {
            AnalysisStatus.DONE -> {
                val effectiveCondition = if (result.isManualOverride && result.manualCondition.isNotBlank())
                    "${result.manualCondition} ✏️" else result.overallCondition

                tvStatus.text           = "✓ Sudah Dianalisis"
                tvStatus.setTextColor("#4CAF50".toColorInt())
                tvResult.text           = conditionDisplay(effectiveCondition)
                tvConfidence.text       = "${(result.confidenceScore * 100).toInt()}% confidence"
                progressConf.progress   = (result.confidenceScore * 100).toInt()
                tvDesc.text             = result.aiDescription
                tvReco.text             = result.aiRecommendation
            }
            AnalysisStatus.ANALYZING -> {
                tvStatus.text = "⏳ Sedang dianalisis..."
                tvResult.text = "—"
            }
            AnalysisStatus.SKIPPED -> {
                tvStatus.text = "⊘ Bukan foto jalan"
                tvStatus.setTextColor("#FF9800".toColorInt())
                tvResult.text = "INVALID"
                tvDesc.text   = result.errorMessage
            }
            AnalysisStatus.FAILED -> {
                tvStatus.text = "❌ Gagal: ${result.errorMessage.take(50)}"
                tvStatus.setTextColor("#F44336".toColorInt())
                tvResult.text = "—"
            }
            else -> {
                tvStatus.text = "Belum dianalisis"
                tvResult.text = "—"
            }
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Detail Foto")
            .setView(dialogView)
            .setPositiveButton("Tutup", null)

        // Tombol Analisis / Re-analisis
        if (result?.status != AnalysisStatus.DONE || result.status == AnalysisStatus.FAILED) {
            builder.setNeutralButton("🔍 Analisis") { _, _ ->
                viewModel.analyzeSingle(item)
            }
        } else {
            builder.setNeutralButton("🔄 Analisis Ulang") { _, _ ->
                viewModel.analyzeSingle(item, forceReanalyze = true)
            }
        }

        // Tombol Override Manual (hanya jika sudah DONE)
        if (result?.status == AnalysisStatus.DONE) {
            builder.setNegativeButton("✏️ Koreksi") { _, _ ->
                showManualOverrideDialog(item.path, result)
            }
        }

        builder.show()
    }

    // ── Manual override dialog ────────────────────────────────────────────

    private fun showManualOverrideDialog(photoPath: String, current: PhotoAnalysisResult) {
        val conditions = arrayOf("BAIK", "SEDANG", "RUSAK_RINGAN", "RUSAK_BERAT")
        var selectedIdx = conditions.indexOfFirst { it == current.overallCondition }.coerceAtLeast(0)

        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_override, null)
        val etNotes    = dialogView.findViewById<EditText>(R.id.etNotes)

        etNotes.setText(current.manualNotes)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("✏️ Koreksi Manual")
            .setMessage("AI mendeteksi: ${current.overallCondition}\nPilih kondisi yang benar:")
            .setSingleChoiceItems(conditions, selectedIdx) { _, idx -> selectedIdx = idx }
            .setView(dialogView)
            .setPositiveButton("Simpan Koreksi") { _, _ ->
                viewModel.saveManualOverride(
                    photoPath = photoPath,
                    condition = conditions[selectedIdx],
                    notes     = etNotes.text.toString()
                )
                Snackbar.make(binding.root, "Koreksi disimpan", Snackbar.LENGTH_SHORT).show()
            }
            .setNeutralButton("Hapus Koreksi") { _, _ ->
                viewModel.clearManualOverride(photoPath)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun conditionDisplay(condition: String): String = when (condition.uppercase()) {
        "BAIK"         -> "✅ BAIK"
        "SEDANG"       -> "🟡 SEDANG"
        "RUSAK_RINGAN" -> "🟠 RUSAK RINGAN"
        "RUSAK_BERAT"  -> "🔴 RUSAK BERAT"
        "CRACK"        -> "🔶 Retak"
        "POTHOLE"      -> "🔴 Lubang"
        "RUTTING"      -> "🟠 Alur"
        "DEPRESSION"   -> "🔴 Amblas"
        "SPALLING"     -> "🟡 Pengelupasan"
        "RAVELING"     -> "🟡 Lepas Agregat"
        "INVALID"      -> "⊘ Bukan Jalan"
        "NORMAL"       -> "✅ Normal"
        else           -> condition
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ADAPTER
// ═══════════════════════════════════════════════════════════════════════════

class PhotoAnalysisAdapter(
    private val onItemClick: (PhotoItem, PhotoAnalysisResult?) -> Unit,
    private val onAnalyze: (PhotoItem) -> Unit
) : RecyclerView.Adapter<PhotoAnalysisAdapter.VH>() {

    private var items: List<Pair<PhotoItem, PhotoAnalysisResult?>> = emptyList()
    private var analyzingPath: String? = null

    fun submitList(list: List<Pair<PhotoItem, PhotoAnalysisResult?>>) {
        items = list
        notifyDataSetChanged()
    }

    fun setAnalyzingPath(path: String?) {
        analyzingPath = path
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_analysis_photo, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (photo, result) = items[position]
        val isAnalyzing     = analyzingPath == photo.path
        holder.bind(photo, result, isAnalyzing, onItemClick, onAnalyze)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val card     : MaterialCardView         = itemView.findViewById(R.id.cardPhoto)
        private val ivThumb  : ImageView                = itemView.findViewById(R.id.ivThumbnail)
        private val tvLabel  : TextView                 = itemView.findViewById(R.id.tvPhotoLabel)
        private val tvSta    : TextView                 = itemView.findViewById(R.id.tvSta)
        private val tvStatus : TextView                 = itemView.findViewById(R.id.tvStatus)
        private val tvResult : TextView                 = itemView.findViewById(R.id.tvResult)
        private val btnAnalyze: MaterialButton          = itemView.findViewById(R.id.btnAnalyze)
        private val progress : ProgressBar              = itemView.findViewById(R.id.progressAnalyzing)

        fun bind(
            photo: PhotoItem,
            result: PhotoAnalysisResult?,
            isAnalyzing: Boolean,
            onItemClick: (PhotoItem, PhotoAnalysisResult?) -> Unit,
            onAnalyze: (PhotoItem) -> Unit
        ) {
            tvLabel.text = photo.label.take(40)
            tvSta.text   = photo.sta

            // Thumbnail — lightweight decode
            val file = File(photo.path)
            if (file.exists()) {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    val bm   = BitmapFactory.decodeFile(photo.path, opts)
                    if (bm != null) ivThumb.setImageBitmap(bm)
                    else ivThumb.setImageResource(android.R.drawable.ic_menu_report_image)
                } catch (_: Exception) {
                    ivThumb.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            } else {
                ivThumb.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            // Status
            when {
                isAnalyzing -> {
                    progress.isVisible = true
                    tvStatus.text      = "⏳ Menganalisis..."
                    tvStatus.setTextColor("#FF9800".toColorInt())
                    tvResult.text      = ""
                    btnAnalyze.isEnabled = false
                }
                result?.status == AnalysisStatus.DONE -> {
                    progress.isVisible = false
                    tvStatus.text      = "✓ Selesai (${(result.confidenceScore * 100).toInt()}%)"
                    tvStatus.setTextColor("#4CAF50".toColorInt())
                    tvResult.text      = buildResultText(result)
                    btnAnalyze.text    = "↺ Ulang"
                    btnAnalyze.isEnabled = true
                }
                result?.status == AnalysisStatus.SKIPPED -> {
                    progress.isVisible = false
                    tvStatus.text      = "⊘ Bukan jalan"
                    tvStatus.setTextColor("#9E9E9E".toColorInt())
                    tvResult.text      = ""
                    btnAnalyze.text    = "🔍 Analisis"
                    btnAnalyze.isEnabled = true
                }
                result?.status == AnalysisStatus.FAILED -> {
                    progress.isVisible = false
                    tvStatus.text      = "❌ Gagal"
                    tvStatus.setTextColor("#F44336".toColorInt())
                    tvResult.text      = ""
                    btnAnalyze.text    = "↺ Coba Lagi"
                    btnAnalyze.isEnabled = true
                }
                else -> {
                    progress.isVisible = false
                    tvStatus.text      = "Belum dianalisis"
                    tvStatus.setTextColor("#9E9E9E".toColorInt())
                    tvResult.text      = ""
                    btnAnalyze.text    = "🔍 Analisis"
                    btnAnalyze.isEnabled = true
                }
            }

            card.setOnClickListener { onItemClick(photo, result) }
            btnAnalyze.setOnClickListener { onAnalyze(photo) }
        }

        private fun buildResultText(result: PhotoAnalysisResult): String {
            val condition = if (result.isManualOverride && result.manualCondition.isNotBlank())
                result.manualCondition else result.overallCondition
            val sev = if (result.dominantSeverity.isNotBlank()) " (${result.dominantSeverity})" else ""
            return "$condition$sev"
        }
    }
}