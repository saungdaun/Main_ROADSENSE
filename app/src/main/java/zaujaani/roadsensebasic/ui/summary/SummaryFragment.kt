package zaujaani.roadsensebasic.ui.summary

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.EventType
import zaujaani.roadsensebasic.data.local.entity.SegmentPci
import zaujaani.roadsensebasic.data.local.entity.SurveyMode
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.databinding.FragmentSummaryBinding
import zaujaani.roadsensebasic.domain.engine.PCIRating
import zaujaani.roadsensebasic.domain.engine.SDICalculator
import zaujaani.roadsensebasic.util.Constants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class SummaryFragment : Fragment() {

    private var _binding: FragmentSummaryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SummaryViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupObservers()

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadSessions()
        }
    }

    private fun setupRecyclerView() {
        // SessionAdapter menerima SessionWithCount (bukan SurveySession langsung)
        // onItemClick  → tap row  → buka detail
        // onDetailClick → tap btnViewDetails → buka session options
        val adapter = SessionAdapter(
            onItemClick   = { item -> showSessionDetail(item.session.id) },
            onDetailClick = { item -> showSessionOptions(item.session) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        viewModel.sessions.observe(viewLifecycleOwner) { sessions ->
            adapter.submitList(sessions)
            binding.tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun setupObservers() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (!isLoading) binding.swipeRefresh.isRefreshing = false
        }

        viewModel.sessionDetail.observe(viewLifecycleOwner) { detail ->
            detail ?: return@observe

            // FIX #1 KRITIS: PCI mode tidak punya dialog sendiri (Bug #1)
            when (detail.mode) {
                SurveyMode.SDI     -> showSdiDetailDialog(detail)
                SurveyMode.PCI     -> showPciDetailDialog(detail)     // ← sebelumnya HILANG
                SurveyMode.GENERAL -> showGeneralDetailDialog(detail)
            }
        }
    }

    private fun showSessionDetail(sessionId: Long) {
        viewModel.loadSessionDetail(sessionId)
    }

    // ── GENERAL MODE DIALOG ───────────────────────────────────────────────

    private fun showGeneralDetailDialog(detail: SessionDetailUi) {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

        sb.appendLine(getString(R.string.detail_date, dateFormat.format(Date(detail.session.startTime))))
        sb.appendLine(getString(R.string.detail_device, detail.session.deviceModel))
        if (detail.session.surveyorName.isNotBlank()) {
            sb.appendLine(getString(R.string.detail_surveyor, detail.session.surveyorName))
        }
        if (detail.session.roadName.isNotBlank()) {
            sb.appendLine("🛣 ${detail.session.roadName}")
        }

        val totalDistText = formatDistance(detail.totalDistance)
        sb.appendLine(getString(R.string.detail_total_distance, totalDistText))
        sb.appendLine(getString(R.string.detail_duration, detail.durationMinutes))
        sb.appendLine(getString(R.string.detail_avg_confidence, detail.avgConfidence))
        sb.appendLine(getString(R.string.detail_photos, detail.photoCount))
        sb.appendLine(getString(R.string.detail_audios, detail.audioCount))
        sb.appendLine()

        sb.appendLine(getString(R.string.detail_condition_header))
        val conditions = listOf(
            Constants.CONDITION_BAIK, Constants.CONDITION_SEDANG,
            Constants.CONDITION_RUSAK_RINGAN, Constants.CONDITION_RUSAK_BERAT
        )
        conditions.forEach { cond ->
            val length = detail.conditionDistribution[cond] ?: 0.0
            if (length > 0) {
                val pct = if (detail.totalDistance > 0) (length / detail.totalDistance * 100).toInt() else 0
                sb.appendLine("  $cond: ${formatDistance(length)} ($pct%)")
            }
        }
        sb.appendLine()

        if (detail.surfaceDistribution.isNotEmpty()) {
            sb.appendLine(getString(R.string.detail_surface_header))
            detail.surfaceDistribution.forEach { (surface, length) ->
                val pct = if (detail.totalDistance > 0) (length / detail.totalDistance * 100).toInt() else 0
                sb.appendLine("  $surface: ${formatDistance(length)} ($pct%)")
            }
        }

        if (detail.events.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("📌 Titik Penting:")
            detail.events.forEach { event ->
                val staStr = formatStaFromMeters(event.distance.toInt())
                val type = when (event.eventType) {
                    EventType.CONDITION_CHANGE -> "🔵 Kondisi: ${event.value}"
                    EventType.SURFACE_CHANGE   -> "🟢 Permukaan: ${event.value}"
                    EventType.PHOTO            -> "📷 Foto"
                    EventType.VOICE            -> "🎤 Rekaman"
                    EventType.NOTE             -> "📝 Catatan: ${event.value}"
                }
                sb.appendLine("  $staStr - $type")
                if (!event.notes.isNullOrBlank()) sb.appendLine("     📝 ${event.notes}")
            }
        }

        val photos = detail.events.filter { it.eventType == EventType.PHOTO && it.value.isNotBlank() }

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.detail_title) + " (Umum)")
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.export_gpx)) { _, _ -> exportSession(detail.session, "gpx") }
            .setNeutralButton(getString(R.string.export_csv)) { _, _ -> exportSession(detail.session, "csv") }

        if (photos.isNotEmpty()) {
            builder.setNegativeButton("Lihat Foto (${photos.size})") { _, _ ->
                showPhotoSlider(photos, 0)
            }
        } else {
            builder.setNegativeButton(getString(R.string.close), null)
        }

        builder.show()
    }

    // ── SDI MODE DIALOG ───────────────────────────────────────────────────

    private fun showSdiDetailDialog(detail: SessionDetailUi) {
        val dialogView     = layoutInflater.inflate(R.layout.dialog_sdi_detail, null)
        val tvAvgSdi       = dialogView.findViewById<TextView>(R.id.tvAvgSdi)
        val progressBar    = dialogView.findViewById<View>(R.id.progressBar)
        val tvTotalDistance = dialogView.findViewById<TextView>(R.id.tvTotalDistance)
        val tvTotalSegments = dialogView.findViewById<TextView>(R.id.tvTotalSegments)
        val tvTotalDistress = dialogView.findViewById<TextView>(R.id.tvTotalDistress)
        val recyclerView   = dialogView.findViewById<RecyclerView>(R.id.recyclerViewSegments)

        tvAvgSdi.text = getString(R.string.sdi_average, detail.averageSdi, SDICalculator.categorizeSDI(detail.averageSdi))
        progressBar.setBackgroundColor(getSdiColor(detail.averageSdi))

        tvTotalDistance.text = "Total Panjang: ${formatDistance(detail.totalDistance)}"
        tvTotalSegments.text = "Total Segmen: ${detail.segmentsSdi.size}"
        tvTotalDistress.text = "Total Kerusakan: ${detail.distressItems.size}"

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        val adapter = SegmentSdiAdapter { segment ->
            showSegmentDetail(segment, detail.distressItems.filter { it.segmentId == segment.id })
        }
        recyclerView.adapter = adapter
        adapter.submitList(detail.segmentsSdi)

        val allPhotoItems = detail.distressItems.filter { it.photoPath.isNotBlank() }

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.detail_title) + " (SDI)")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.export_gpx)) { _, _ -> exportSession(detail.session, "gpx") }
            .setNeutralButton(getString(R.string.export_csv)) { _, _ -> exportSession(detail.session, "csv") }

        if (allPhotoItems.isNotEmpty()) {
            builder.setNegativeButton("Lihat Semua Foto (${allPhotoItems.size})") { _, _ ->
                showDistressPhotoSlider(allPhotoItems, 0)
            }
        } else {
            builder.setNegativeButton(getString(R.string.close), null)
        }

        builder.show()
    }

    // ── PCI MODE DIALOG — FIX #1 (sebelumnya HILANG sama sekali) ─────────

    private fun showPciDetailDialog(detail: SessionDetailUi) {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

        // Header
        sb.appendLine("═══════════════════════════════")
        sb.appendLine("    LAPORAN SURVEI PCI")
        sb.appendLine("    ASTM D6433")
        sb.appendLine("═══════════════════════════════")
        sb.appendLine()
        sb.appendLine("📅 ${dateFormat.format(Date(detail.session.startTime))}")
        if (detail.session.surveyorName.isNotBlank()) sb.appendLine("👤 ${detail.session.surveyorName}")
        if (detail.session.roadName.isNotBlank())    sb.appendLine("🛣 ${detail.session.roadName}")
        sb.appendLine("📏 Panjang: ${formatDistance(detail.totalDistance)}")
        sb.appendLine("⏱ Durasi: ${detail.durationMinutes} menit")
        sb.appendLine()

        // Skor PCI
        val pci    = detail.averagePci
        val rating = detail.pciRating ?: PCIRating.fromScore(if (pci >= 0) pci else 0)
        val pciEmoji = when {
            pci >= 86 -> "🟢"
            pci >= 71 -> "🟩"
            pci >= 56 -> "🟡"
            pci >= 41 -> "🟠"
            pci >= 26 -> "🔴"
            pci >= 0  -> "⛔"
            else      -> "—"
        }

        if (pci >= 0) {
            sb.appendLine("┌─────────────────────────────┐")
            sb.appendLine("│  $pciEmoji PCI Score: $pci / 100")
            sb.appendLine("│  Kondisi: ${rating.displayName}")
            sb.appendLine("│  Tindakan: ${rating.actionRequired}")
            sb.appendLine("└─────────────────────────────┘")
        } else {
            sb.appendLine("⚠️ Belum ada data PCI tersedia (tidak ada input distress).")
        }
        sb.appendLine()

        // Distribusi kondisi
        if (detail.segmentsPci.isNotEmpty()) {
            val scored = detail.segmentsPci.filter { it.pciScore >= 0 }
            sb.appendLine("📊 Distribusi Segmen (${scored.size}/${detail.segmentsPci.size} terskor):")
            PCIRating.entries.forEach { r ->
                val count = scored.count { it.pciScore in r.range }
                if (count > 0) {
                    val pct = (count * 100 / scored.size)
                    sb.appendLine("  ${r.displayName}: $count segmen ($pct%)")
                }
            }
            sb.appendLine()
        }

        // Tabel segmen
        if (detail.segmentsPci.isNotEmpty()) {
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("  STA          PCI  Kondisi    Kerusakan")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            detail.segmentsPci.forEach { seg ->
                val r = if (seg.pciScore >= 0) PCIRating.fromScore(seg.pciScore) else null
                val pciStr   = if (seg.pciScore >= 0) "${seg.pciScore}" else " – "
                val ratingStr = r?.displayName?.take(11)?.padEnd(11) ?: "Belum  disurvey"
                val distCount = "${seg.distressCount}".padStart(2)
                sb.appendLine("  ${seg.startSta}–${seg.endSta}  $pciStr   $ratingStr  $distCount item")
                if (seg.dominantDistressType.isNotBlank()) {
                    sb.appendLine("    ↳ Dominan: ${seg.dominantDistressType}")
                }
            }
            sb.appendLine()
        }

        // Ringkasan distress
        if (detail.pciDistressItems.isNotEmpty()) {
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("  RINGKASAN KERUSAKAN (${detail.pciDistressItems.size} item)")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            detail.pciDistressItems.forEachIndexed { i, item ->
                sb.appendLine("${i + 1}. ${item.type.displayName}")
                sb.appendLine("   Severity: ${item.severity.name} | Kuantitas: ${String.format(Locale.US, "%.2f", item.quantity)} ${item.type.unitLabel}")
                sb.appendLine("   Density: ${String.format(Locale.US, "%.1f", item.density)}% | DV: ${String.format(Locale.US, "%.1f", item.deductValue)}")
                sb.appendLine("   STA: ${item.sta}")
                if (item.notes.isNotBlank()) sb.appendLine("   📝 ${item.notes}")
            }
            sb.appendLine()
        }

        // Rekomendasi
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("  REKOMENDASI PENANGANAN")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        if (pci >= 0) {
            sb.appendLine(rating.actionRequired)
            sb.appendLine()
            sb.appendLine("Metodologi: ASTM D6433")
            sb.appendLine("Segmen: 50m × lebar lajur")
        } else {
            sb.appendLine("Input data distress diperlukan untuk rekomendasi.")
        }

        // Photo items dari PCI distress
        val pciPhotoItems = detail.pciDistressItems.filter { it.photoPath.isNotBlank() }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Detail Survey PCI")
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.export_pdf)) { _, _ ->
                exportSession(detail.session, "pdf")
            }
            .setNeutralButton(getString(R.string.export_csv)) { _, _ ->
                exportSession(detail.session, "csv")
            }
            .setNegativeButton(
                if (pciPhotoItems.isNotEmpty()) "Lihat Foto (${pciPhotoItems.size})" else getString(R.string.close)
            ) { _, _ ->
                if (pciPhotoItems.isNotEmpty()) {
                    showPciPhotoSlider(pciPhotoItems, 0)
                }
            }
            .show()
    }

    // ── SEGMENT DETAIL (SDI) ──────────────────────────────────────────────

    private fun showSegmentDetail(
        segment: zaujaani.roadsensebasic.data.local.entity.SegmentSdi,
        distressItems: List<zaujaani.roadsensebasic.data.local.entity.DistressItem>
    ) {
        val sb = StringBuilder()
        sb.appendLine("Segmen: ${segment.startSta} – ${segment.endSta}")
        sb.appendLine("SDI: ${segment.sdiScore} (${SDICalculator.categorizeSDI(segment.sdiScore)})")
        sb.appendLine("Jumlah kerusakan: ${segment.distressCount}")
        sb.appendLine()
        if (distressItems.isNotEmpty()) {
            sb.appendLine("Daftar Kerusakan:")
            distressItems.forEachIndexed { i, item ->
                sb.appendLine("${i+1}. ${item.type.name} – ${item.severity.name}")
                sb.appendLine("   ${item.lengthOrArea} m/m², STA ${item.sta}")
                if (item.photoPath.isNotBlank()) sb.appendLine("   📷 Foto tersedia")
                if (item.audioPath.isNotBlank()) sb.appendLine("   🎤 Rekaman tersedia")
            }
        } else {
            sb.appendLine("Tidak ada kerusakan.")
        }

        val photoItems = distressItems.filter { it.photoPath.isNotBlank() }
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Detail Segmen")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
        if (photoItems.isNotEmpty()) {
            builder.setNeutralButton("Lihat Foto (${photoItems.size})") { _, _ ->
                showDistressPhotoSlider(photoItems, 0)
            }
        }
        builder.show()
    }

    // ── PHOTO SLIDERS ─────────────────────────────────────────────────────

    /**
     * Photo slider untuk DistressItem (SDI mode).
     * FIX #3 OOM: gunakan BitmapFactory.Options dengan inSampleSize
     */
    private fun showDistressPhotoSlider(
        photos: List<zaujaani.roadsensebasic.data.local.entity.DistressItem>,
        startIndex: Int
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_photo_slider, null)
        val imageView  = dialogView.findViewById<ImageView>(R.id.ivPhoto)
        val tvCounter  = dialogView.findViewById<TextView>(R.id.tvCounter)
        val btnPrev    = dialogView.findViewById<MaterialButton>(R.id.btnPrev)
        val btnNext    = dialogView.findViewById<MaterialButton>(R.id.btnNext)

        var currentIndex = startIndex

        fun loadPhoto(index: Int) {
            val item = photos[index]
            val file = File(item.photoPath)
            if (file.exists()) {
                // FIX #3: decode dengan inSampleSize=4 untuk hindari OOM
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bitmap = BitmapFactory.decodeFile(item.photoPath, opts)
                imageView.setImageBitmap(bitmap)
                tvCounter.text = "${index + 1}/${photos.size} - STA ${item.sta}"
            } else {
                Toast.makeText(requireContext(), "File foto tidak ditemukan", Toast.LENGTH_SHORT).show()
            }
            btnPrev.isEnabled = index > 0
            btnNext.isEnabled = index < photos.size - 1
        }

        btnPrev.setOnClickListener { if (currentIndex > 0) { currentIndex--; loadPhoto(currentIndex) } }
        btnNext.setOnClickListener { if (currentIndex < photos.size - 1) { currentIndex++; loadPhoto(currentIndex) } }

        loadPhoto(currentIndex)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.photo_preview_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    /**
     * Photo slider untuk PCIDistressItem.
     */
    private fun showPciPhotoSlider(
        photos: List<zaujaani.roadsensebasic.data.local.entity.PCIDistressItem>,
        startIndex: Int
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_photo_slider, null)
        val imageView  = dialogView.findViewById<ImageView>(R.id.ivPhoto)
        val tvCounter  = dialogView.findViewById<TextView>(R.id.tvCounter)
        val btnPrev    = dialogView.findViewById<MaterialButton>(R.id.btnPrev)
        val btnNext    = dialogView.findViewById<MaterialButton>(R.id.btnNext)

        var currentIndex = startIndex

        fun loadPhoto(index: Int) {
            val item = photos[index]
            val file = File(item.photoPath)
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bitmap = BitmapFactory.decodeFile(item.photoPath, opts)
                imageView.setImageBitmap(bitmap)
                tvCounter.text = "${index + 1}/${photos.size} - ${item.type.displayName} | STA ${item.sta}"
            } else {
                Toast.makeText(requireContext(), "File foto tidak ditemukan", Toast.LENGTH_SHORT).show()
            }
            btnPrev.isEnabled = index > 0
            btnNext.isEnabled = index < photos.size - 1
        }

        btnPrev.setOnClickListener { if (currentIndex > 0) { currentIndex--; loadPhoto(currentIndex) } }
        btnNext.setOnClickListener { if (currentIndex < photos.size - 1) { currentIndex++; loadPhoto(currentIndex) } }

        loadPhoto(currentIndex)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Foto Kerusakan PCI")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    /**
     * Photo slider untuk RoadEvent (GENERAL mode).
     */
    private fun showPhotoSlider(
        photos: List<zaujaani.roadsensebasic.data.local.entity.RoadEvent>,
        startIndex: Int
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_photo_slider, null)
        val imageView  = dialogView.findViewById<ImageView>(R.id.ivPhoto)
        val tvCounter  = dialogView.findViewById<TextView>(R.id.tvCounter)
        val btnPrev    = dialogView.findViewById<MaterialButton>(R.id.btnPrev)
        val btnNext    = dialogView.findViewById<MaterialButton>(R.id.btnNext)

        var currentIndex = startIndex

        fun loadPhoto(index: Int) {
            val event = photos[index]
            val file  = File(event.value)
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bitmap = BitmapFactory.decodeFile(event.value, opts)
                imageView.setImageBitmap(bitmap)
                tvCounter.text = "${index + 1}/${photos.size} - ${formatStaFromMeters(event.distance.toInt())}"
            } else {
                Toast.makeText(requireContext(), "File foto tidak ditemukan", Toast.LENGTH_SHORT).show()
            }
            btnPrev.isEnabled = index > 0
            btnNext.isEnabled = index < photos.size - 1
        }

        btnPrev.setOnClickListener { if (currentIndex > 0) { currentIndex--; loadPhoto(currentIndex) } }
        btnNext.setOnClickListener { if (currentIndex < photos.size - 1) { currentIndex++; loadPhoto(currentIndex) } }

        loadPhoto(currentIndex)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.photo_preview_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    // ── SESSION OPTIONS & EXPORT ──────────────────────────────────────────

    private fun showSessionOptions(session: SurveySession) {
        val items = arrayOf(
            getString(R.string.view_details),
            getString(R.string.export_gpx),
            getString(R.string.export_csv),
            getString(R.string.export_pdf),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.session_options))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSessionDetail(session.id)
                    1 -> exportSession(session, "gpx")
                    2 -> exportSession(session, "csv")
                    3 -> exportSession(session, "pdf")
                    4 -> confirmDelete(session)
                }
            }
            .show()
    }

    private fun exportSession(session: SurveySession, format: String) {
        when (format) {
            "gpx" -> viewModel.exportSessionToGpx(session.id) { file ->
                if (file != null) showExportSuccess(file.absolutePath)
                else Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
            "csv" -> viewModel.exportSessionToCsv(session.id) { file ->
                if (file != null) showExportSuccess(file.absolutePath)
                else Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
            "pdf" -> viewModel.exportSessionToPdf(session.id) { file ->
                if (file != null) showExportSuccess(file.absolutePath)
                else Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showExportSuccess(path: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.export_success))
            .setMessage(getString(R.string.export_success_message, path))
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun confirmDelete(session: SurveySession) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_session))
            .setMessage(getString(R.string.delete_session_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> viewModel.deleteSession(session) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            getString(R.string.distance_m_format, meters.toInt())
        } else {
            getString(R.string.distance_km_format, meters / 1000.0)
        }
    }

    /**
     * FIX W5: Format STA yang benar.
     * Contoh: 1500m → "STA 1+500" (bukan "STA 15+0")
     */
    private fun formatStaFromMeters(meters: Int): String {
        val km = meters / 1000
        val m  = meters % 1000
        return "STA %d+%03d".format(km, m)   // FIX: /1000 dan %1000, bukan /100 dan %100
    }

    private fun getSdiColor(sdi: Int): Int = when (sdi) {
        in 0..20  -> Color.GREEN
        in 21..40 -> Color.parseColor("#8BC34A")
        in 41..60 -> Color.YELLOW
        in 61..80 -> Color.parseColor("#FF9800")
        else      -> Color.RED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}