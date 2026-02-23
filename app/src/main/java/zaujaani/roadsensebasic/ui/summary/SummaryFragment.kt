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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.EventType
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

/**
 * SummaryFragment — Daftar sesi survey dan detail per sesi.
 *
 * FOTO: Semua mode (GENERAL, SDI, PCI) mendukung lihat foto dari dalam app.
 *   - Photo slider menampilkan caption: tipe kerusakan, STA, severity, notes.
 *   - SDI/PCI: foto dari distress items + foto FAB kamera umum (overview lapangan).
 *   - Segmen SDI: tombol foto muncul jika ada foto di segmen itu.
 *   - GENERAL: semua foto dari EventType.PHOTO.
 */
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
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadSessions() }
    }

    private fun setupRecyclerView() {
        val adapter = SessionAdapter(
            onItemClick   = { item -> viewModel.loadSessionDetail(item.session.id) },
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
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            if (!loading) binding.swipeRefresh.isRefreshing = false
        }
        viewModel.sessionDetail.observe(viewLifecycleOwner) { detail ->
            detail ?: return@observe
            when (detail.mode) {
                SurveyMode.SDI     -> showSdiDetailDialog(detail)
                SurveyMode.PCI     -> showPciDetailDialog(detail)
                SurveyMode.GENERAL -> showGeneralDetailDialog(detail)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // GENERAL DIALOG
    // ══════════════════════════════════════════════════════════════════════

    private fun showGeneralDetailDialog(detail: SessionDetailUi) {
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        sb.appendLine(getString(R.string.detail_date, fmt.format(Date(detail.session.startTime))))
        sb.appendLine(getString(R.string.detail_device, detail.session.deviceModel))
        if (detail.session.surveyorName.isNotBlank()) sb.appendLine(getString(R.string.detail_surveyor, detail.session.surveyorName))
        if (detail.session.roadName.isNotBlank())     sb.appendLine("🛣 ${detail.session.roadName}")
        sb.appendLine(getString(R.string.detail_total_distance, formatDistance(detail.totalDistance)))
        sb.appendLine(getString(R.string.detail_duration, detail.durationMinutes))
        sb.appendLine(getString(R.string.detail_avg_confidence, detail.avgConfidence))
        sb.appendLine(getString(R.string.detail_photos, detail.photoCount))
        sb.appendLine(getString(R.string.detail_audios, detail.audioCount))
        sb.appendLine()
        sb.appendLine(getString(R.string.detail_condition_header))
        listOf(Constants.CONDITION_BAIK, Constants.CONDITION_SEDANG,
            Constants.CONDITION_RUSAK_RINGAN, Constants.CONDITION_RUSAK_BERAT).forEach { cond ->
            val length = detail.conditionDistribution[cond] ?: 0.0
            if (length > 0) {
                val pct = if (detail.totalDistance > 0) (length / detail.totalDistance * 100).toInt() else 0
                sb.appendLine("  $cond: ${formatDistance(length)} ($pct%)")
            }
        }
        if (detail.surfaceDistribution.isNotEmpty()) {
            sb.appendLine(); sb.appendLine(getString(R.string.detail_surface_header))
            detail.surfaceDistribution.forEach { (surf, len) ->
                val pct = if (detail.totalDistance > 0) (len / detail.totalDistance * 100).toInt() else 0
                sb.appendLine("  $surf: ${formatDistance(len)} ($pct%)")
            }
        }
        if (detail.events.isNotEmpty()) {
            sb.appendLine(); sb.appendLine("📌 Titik Penting:")
            detail.events.forEach { ev ->
                val tag = when (ev.eventType) {
                    EventType.CONDITION_CHANGE -> "🔵 Kondisi: ${ev.value}"
                    EventType.SURFACE_CHANGE   -> "🟢 Permukaan: ${ev.value}"
                    EventType.PHOTO            -> "📷 Foto"
                    EventType.VOICE            -> "🎤 Rekaman"
                    EventType.NOTE             -> "📝 ${ev.value}"
                }
                sb.appendLine("  ${formatSta(ev.distance.toInt())} — $tag")
                if (!ev.notes.isNullOrBlank()) sb.appendLine("     📝 ${ev.notes}")
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${getString(R.string.detail_title)} — Umum")
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.export_pdf)) { _, _ -> exportSession(detail.session, "pdf") }
            .setNeutralButton(getString(R.string.export_csv))  { _, _ -> exportSession(detail.session, "csv") }
            .apply {
                if (detail.allPhotos.isNotEmpty()) {
                    setNegativeButton("📷 Foto (${detail.allPhotos.size})") { _, _ ->
                        showPhotoSlider(detail.allPhotos, 0)
                    }
                } else {
                    setNegativeButton(getString(R.string.close), null)
                }
            }
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════
    // SDI DIALOG
    // ══════════════════════════════════════════════════════════════════════

    private fun showSdiDetailDialog(detail: SessionDetailUi) {
        val dialogView      = layoutInflater.inflate(R.layout.dialog_sdi_detail, null)
        val tvAvgSdi        = dialogView.findViewById<TextView>(R.id.tvAvgSdi)
        val progressBar     = dialogView.findViewById<View>(R.id.progressBar)
        val tvTotalDistance = dialogView.findViewById<TextView>(R.id.tvTotalDistance)
        val tvTotalSegments = dialogView.findViewById<TextView>(R.id.tvTotalSegments)
        val tvTotalDistress = dialogView.findViewById<TextView>(R.id.tvTotalDistress)
        val recyclerView    = dialogView.findViewById<RecyclerView>(R.id.recyclerViewSegments)

        tvAvgSdi.text = getString(R.string.sdi_average, detail.averageSdi, SDICalculator.categorizeSDI(detail.averageSdi))
        progressBar.setBackgroundColor(getSdiColor(detail.averageSdi))
        tvTotalDistance.text = "Total Panjang: ${formatDistance(detail.totalDistance)}"
        tvTotalSegments.text = "Total Segmen: ${detail.segmentsSdi.size}"
        tvTotalDistress.text = "Total Kerusakan: ${detail.distressItems.size}"

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        val segAdapter = SegmentSdiAdapter { segment ->
            val segDistress = detail.distressItems.filter { it.segmentId == segment.id }
            val segPhotos   = buildSegmentPhotos(segDistress, "SDI")
            showSdiSegmentDetail(segment, segDistress, segPhotos)
        }
        recyclerView.adapter = segAdapter
        segAdapter.submitList(detail.segmentsSdi)

        val totalPhotos = detail.allPhotos.size
        // Hitung berapa foto yang berasal dari distress (informasi berguna di judul)
        val distressPhotoCount = detail.allPhotos.count { it.source == PhotoItem.Source.DISTRESS }
        val generalPhotoCount  = detail.allPhotos.count { it.source == PhotoItem.Source.GENERAL }
        val photoLabel = buildString {
            append("📷 Semua Foto ($totalPhotos)")
            if (distressPhotoCount > 0 && generalPhotoCount > 0) {
                append("  ·  Kerusakan: $distressPhotoCount  Lapangan: $generalPhotoCount")
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${getString(R.string.detail_title)} — SDI (${detail.segmentsSdi.size} segmen)")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.export_pdf)) { _, _ -> exportSession(detail.session, "pdf") }
            .setNeutralButton(getString(R.string.export_csv))  { _, _ -> exportSession(detail.session, "csv") }
            .apply {
                if (totalPhotos > 0) {
                    setNegativeButton(photoLabel) { _, _ -> showPhotoSlider(detail.allPhotos, 0) }
                } else {
                    setNegativeButton(getString(R.string.close), null)
                }
            }
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PCI DIALOG
    // ══════════════════════════════════════════════════════════════════════

    private fun showPciDetailDialog(detail: SessionDetailUi) {
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

        sb.appendLine("═══════════════════════════════")
        sb.appendLine("    LAPORAN SURVEI PCI")
        sb.appendLine("═══════════════════════════════")
        sb.appendLine()
        sb.appendLine("📅 ${fmt.format(Date(detail.session.startTime))}")
        if (detail.session.surveyorName.isNotBlank()) sb.appendLine("👤 ${detail.session.surveyorName}")
        if (detail.session.roadName.isNotBlank())     sb.appendLine("🛣 ${detail.session.roadName}")
        sb.appendLine("📏 Panjang: ${formatDistance(detail.totalDistance)}")
        sb.appendLine("⏱ Durasi: ${detail.durationMinutes} menit")
        sb.appendLine()

        val pci    = detail.averagePci
        val rating = detail.pciRating ?: PCIRating.fromScore(if (pci >= 0) pci else 0)
        val emoji  = when { pci>=86->"🟢"; pci>=71->"🟩"; pci>=56->"🟡"; pci>=41->"🟠"; pci>=26->"🔴"; pci>=0->"⛔"; else->"—" }

        if (pci >= 0) {
            sb.appendLine("┌─────────────────────────────┐")
            sb.appendLine("│  $emoji PCI Score: $pci / 100")
            sb.appendLine("│  Kondisi: ${rating.displayName}")
            sb.appendLine("│  Tindakan: ${rating.actionRequired}")
            sb.appendLine("└─────────────────────────────┘")
        } else {
            sb.appendLine("⚠️ Belum ada data PCI.")
        }
        sb.appendLine()

        if (detail.segmentsPci.isNotEmpty()) {
            val scored = detail.segmentsPci.filter { it.pciScore >= 0 }
            sb.appendLine("📊 Distribusi Segmen (${scored.size}/${detail.segmentsPci.size}):")
            PCIRating.entries.forEach { r ->
                val count = scored.count { it.pciScore in r.range }
                if (count > 0) sb.appendLine("  ${r.displayName}: $count segmen (${count * 100 / scored.size}%)")
            }
            sb.appendLine()
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("  STA             PCI   Kondisi")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            detail.segmentsPci.forEach { seg ->
                val r      = if (seg.pciScore >= 0) PCIRating.fromScore(seg.pciScore) else null
                val pciStr = if (seg.pciScore >= 0) "${seg.pciScore}" else "—"
                val ratStr = r?.displayName?.take(13)?.padEnd(13) ?: "Belum disurvey"
                sb.appendLine("  ${seg.startSta}–${seg.endSta}  $pciStr   $ratStr  (${seg.distressCount})")
                if (seg.dominantDistressType.isNotBlank()) sb.appendLine("    ↳ ${seg.dominantDistressType}")
            }
            sb.appendLine()
        }

        if (detail.pciDistressItems.isNotEmpty()) {
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("  KERUSAKAN (${detail.pciDistressItems.size} item)")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            detail.pciDistressItems.forEachIndexed { i, item ->
                val hasPhoto = item.photoPath?.let { it.isNotBlank() && File(it).exists() } == true
                sb.appendLine("${i + 1}. ${item.type.displayName}${if (hasPhoto) " 📷" else ""}")
                sb.appendLine("   ${item.severity.name}  |  ${String.format(Locale.US, "%.2f", item.quantity)} ${item.type.unitLabel}")
                sb.appendLine("   Density: ${String.format(Locale.US, "%.1f", item.density)}%  DV: ${String.format(Locale.US, "%.1f", item.deductValue)}")
                sb.appendLine("   ${item.sta}")
                if (item.notes.isNotBlank()) sb.appendLine("   📝 ${item.notes}")
            }
            sb.appendLine()
        }

        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine("  REKOMENDASI")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine(if (pci >= 0) rating.actionRequired else "Input distress diperlukan.")

        val totalPhotos = detail.allPhotos.size

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Detail Survey PCI")
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.export_pdf)) { _, _ -> exportSession(detail.session, "pdf") }
            .setNeutralButton(getString(R.string.export_csv))  { _, _ -> exportSession(detail.session, "csv") }
            .apply {
                if (totalPhotos > 0) {
                    setNegativeButton("📷 Semua Foto ($totalPhotos)") { _, _ ->
                        showPhotoSlider(detail.allPhotos, 0)
                    }
                } else {
                    setNegativeButton(getString(R.string.close), null)
                }
            }
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════
    // SDI SEGMENT DETAIL — per segmen, foto per segmen bisa dibuka
    // ══════════════════════════════════════════════════════════════════════

    private fun showSdiSegmentDetail(
        segment: zaujaani.roadsensebasic.data.local.entity.SegmentSdi,
        distressItems: List<zaujaani.roadsensebasic.data.local.entity.DistressItem>,
        segmentPhotos: List<PhotoItem>
    ) {
        val sb = StringBuilder()
        sb.appendLine("Segmen: ${segment.startSta} – ${segment.endSta}")
        sb.appendLine("SDI: ${segment.sdiScore}  (${SDICalculator.categorizeSDI(segment.sdiScore)})")
        sb.appendLine("Jumlah kerusakan: ${segment.distressCount}")
        sb.appendLine()
        if (distressItems.isNotEmpty()) {
            sb.appendLine("Daftar Kerusakan:")
            distressItems.forEachIndexed { i, item ->
                val hasPhoto = item.photoPath?.let { it.isNotBlank() && File(it).exists() } == true
                sb.appendLine("${i + 1}. ${item.type.name} — ${item.severity.name}${if (hasPhoto) " 📷" else ""}")
                sb.appendLine("   ${item.lengthOrArea} m/m²  |  ${item.sta}")
                if (!item.notes.isNullOrBlank()) sb.appendLine("   📝 ${item.notes}")
                if (item.audioPath.isNotBlank()) sb.appendLine("   🎤 Ada rekaman")
            }
        } else {
            sb.appendLine("Tidak ada kerusakan tercatat.")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Detail Segmen SDI")
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.close), null)
            .apply {
                if (segmentPhotos.isNotEmpty()) {
                    setNegativeButton("📷 Foto Segmen (${segmentPhotos.size})") { _, _ ->
                        showPhotoSlider(segmentPhotos, 0)
                    }
                }
            }
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PHOTO SLIDER — universal untuk semua mode
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Photo slider universal menggunakan [PhotoItem].
     *
     * Menampilkan:
     * - Gambar dengan inSampleSize=2 (aman, tidak OOM, kualitas cukup)
     * - Caption multi-baris: index/total, STA, label, notes
     * - Prev/Next navigation
     */
    private fun showPhotoSlider(photos: List<PhotoItem>, startIndex: Int) {
        if (photos.isEmpty()) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_photo_slider, null)
        val imageView  = dialogView.findViewById<ImageView>(R.id.ivPhoto)
        val tvCounter  = dialogView.findViewById<TextView>(R.id.tvCounter)
        val btnPrev    = dialogView.findViewById<MaterialButton>(R.id.btnPrev)
        val btnNext    = dialogView.findViewById<MaterialButton>(R.id.btnNext)

        var idx = startIndex.coerceIn(0, photos.lastIndex)

        fun load(i: Int) {
            val item = photos[i]
            val file = File(item.path)
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                imageView.setImageBitmap(BitmapFactory.decodeFile(item.path, opts))
            } else {
                imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                Timber.w("Foto tidak ditemukan: ${item.path}")
            }
            tvCounter.text = item.caption(i, photos.size)
            btnPrev.isEnabled = i > 0
            btnNext.isEnabled = i < photos.lastIndex
        }

        btnPrev.setOnClickListener { if (idx > 0)              { idx--; load(idx) } }
        btnNext.setOnClickListener { if (idx < photos.lastIndex) { idx++; load(idx) } }
        load(idx)

        val hasDistress = photos.any { it.source == PhotoItem.Source.DISTRESS }
        val hasGeneral  = photos.any { it.source == PhotoItem.Source.GENERAL }
        val title = when {
            hasDistress && hasGeneral -> "Foto Survey (${photos.size})"
            hasDistress               -> "Dokumentasi Kerusakan (${photos.size})"
            else                      -> "Foto Lapangan (${photos.size})"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════
    // SESSION OPTIONS & EXPORT
    // ══════════════════════════════════════════════════════════════════════

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
                    0 -> viewModel.loadSessionDetail(session.id)
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

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /** Bangun PhotoItem list untuk satu segmen SDI (dipakai di detail segmen). */
    private fun buildSegmentPhotos(
        distressItems: List<zaujaani.roadsensebasic.data.local.entity.DistressItem>,
        modeLabel: String
    ): List<PhotoItem> = distressItems.mapNotNull { item ->
        val path = item.photoPath ?: return@mapNotNull null
        if (path.isBlank() || !File(path).exists()) return@mapNotNull null
        PhotoItem(
            path   = path,
            label  = "${item.type.displayName} (${item.severity.name}) | $modeLabel",
            sta    = item.sta,
            notes  = item.notes ?: "",
            source = PhotoItem.Source.DISTRESS
        )
    }

    private fun formatDistance(meters: Double): String = if (meters < 1000) {
        getString(R.string.distance_m_format, meters.toInt())
    } else {
        getString(R.string.distance_km_format, meters / 1000.0)
    }

    private fun formatSta(meters: Int): String {
        val km = meters / 1000; val m = meters % 1000
        return "STA %d+%03d".format(km, m)
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