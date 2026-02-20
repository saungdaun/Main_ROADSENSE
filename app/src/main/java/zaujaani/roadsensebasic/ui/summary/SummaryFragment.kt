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
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.EventType
import zaujaani.roadsensebasic.data.local.entity.SurveyMode
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.databinding.FragmentSummaryBinding
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
    private lateinit var adapter: SessionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        adapter = SessionAdapter(
            onItemClick = { item ->
                showSessionDetail(item.session.id)
            },
            onDetailClick = { item ->
                showSessionOptions(item.session)
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadSessions()
        }
    }

    private fun observeViewModel() {
        viewModel.sessions.observe(viewLifecycleOwner) { sessions ->
            adapter.submitList(sessions)
            binding.tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
            binding.swipeRefresh.isRefreshing = false
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (!isLoading) binding.swipeRefresh.isRefreshing = false
        }

        viewModel.sessionDetail.observe(viewLifecycleOwner) { detail ->
            detail ?: return@observe
            if (detail.mode == SurveyMode.SDI) {
                showSdiDetailDialog(detail)
            } else {
                showGeneralDetailDialog(detail)
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

        val totalDistText = if (detail.totalDistance < 1000) {
            getString(R.string.distance_m_format, detail.totalDistance.toInt())
        } else {
            getString(R.string.distance_km_format, detail.totalDistance / 1000.0)
        }
        sb.appendLine(getString(R.string.detail_total_distance, totalDistText))
        sb.appendLine(getString(R.string.detail_duration, detail.durationMinutes))
        sb.appendLine(getString(R.string.detail_avg_confidence, detail.avgConfidence))
        sb.appendLine(getString(R.string.detail_photos, detail.photoCount))
        sb.appendLine(getString(R.string.detail_audios, detail.audioCount))
        sb.appendLine()

        sb.appendLine(getString(R.string.detail_condition_header))
        val conditions = listOf(
            Constants.CONDITION_BAIK,
            Constants.CONDITION_SEDANG,
            Constants.CONDITION_RUSAK_RINGAN,
            Constants.CONDITION_RUSAK_BERAT
        )
        conditions.forEach { cond ->
            val length = detail.conditionDistribution[cond] ?: 0.0
            if (length > 0) {
                val pct = if (detail.totalDistance > 0) (length / detail.totalDistance * 100).toInt() else 0
                val lengthText = if (length < 1000) {
                    getString(R.string.distance_m_format, length.toInt())
                } else {
                    getString(R.string.distance_km_format, length / 1000.0)
                }
                sb.appendLine("  $cond: $lengthText ($pct%)")
            }
        }
        sb.appendLine()

        if (detail.surfaceDistribution.isNotEmpty()) {
            sb.appendLine(getString(R.string.detail_surface_header))
            detail.surfaceDistribution.forEach { (surface, length) ->
                val pct = if (detail.totalDistance > 0) (length / detail.totalDistance * 100).toInt() else 0
                val lengthText = if (length < 1000) {
                    getString(R.string.distance_m_format, length.toInt())
                } else {
                    getString(R.string.distance_km_format, length / 1000.0)
                }
                sb.appendLine("  $surface: $lengthText ($pct%)")
            }
        }

        if (detail.events.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("📌 Titik Penting:")
            detail.events.forEach { event ->
                val sta = event.distance.toInt()
                val staStr = "STA ${sta/100}+${sta%100}"
                val type = when (event.eventType) {
                    EventType.CONDITION_CHANGE -> "🔵 Kondisi: ${event.value}"
                    EventType.SURFACE_CHANGE -> "🟢 Permukaan: ${event.value}"
                    EventType.PHOTO -> "📷 Foto"
                    EventType.VOICE -> "🎤 Rekaman"
                }
                sb.appendLine("  $staStr - $type")
                if (!event.notes.isNullOrBlank()) {
                    sb.appendLine("     📝 ${event.notes}")
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.detail_title) + " (Umum)")
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.export_gpx)) { _, _ ->
                exportSession(detail.session, "gpx")
            }
            .setNeutralButton(getString(R.string.export_csv)) { _, _ ->
                exportSession(detail.session, "csv")
            }
            .setNegativeButton(getString(R.string.view_photos)) { _, _ ->
                val photoEvents = detail.events.filter { it.eventType == EventType.PHOTO }
                if (photoEvents.isNotEmpty()) showPhotoSlider(photoEvents, 0)
                else Toast.makeText(requireContext(), "Tidak ada foto", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ── SDI MODE DIALOG ───────────────────────────────────────────────────

    private fun showSdiDetailDialog(detail: SessionDetailUi) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_sdi_detail, null)
        val tvAvgSdi = dialogView.findViewById<TextView>(R.id.tvAvgSdi)
        val progressBar = dialogView.findViewById<View>(R.id.progressBar)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerViewSegments)

        tvAvgSdi.text = getString(R.string.sdi_average, detail.averageSdi, SDICalculator.categorizeSDI(detail.averageSdi))
        // Ganti getCategoryColor dengan fungsi lokal
        progressBar.setBackgroundColor(getSdiColor(detail.averageSdi))

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        val adapter = SegmentSdiAdapter { segment ->
            showSegmentDetail(segment, detail.distressItems.filter { it.segmentId == segment.id })
        }
        recyclerView.adapter = adapter
        adapter.submitList(detail.segmentsSdi)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.detail_title) + " (SDI)")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.export_gpx)) { _, _ ->
                exportSession(detail.session, "gpx")
            }
            .setNeutralButton(getString(R.string.export_csv)) { _, _ ->
                exportSession(detail.session, "csv")
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showSegmentDetail(segment: zaujaani.roadsensebasic.data.local.entity.SegmentSdi, distressItems: List<zaujaani.roadsensebasic.data.local.entity.DistressItem>) {
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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Detail Segmen")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    // ── PHOTO SLIDER ──────────────────────────────────────────────────────

    private fun showPhotoSlider(photos: List<zaujaani.roadsensebasic.data.local.entity.RoadEvent>, startIndex: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_photo_slider, null)
        val imageView = dialogView.findViewById<ImageView>(R.id.ivPhoto)
        val tvCounter = dialogView.findViewById<TextView>(R.id.tvCounter)
        val btnPrev = dialogView.findViewById<MaterialButton>(R.id.btnPrev)
        val btnNext = dialogView.findViewById<MaterialButton>(R.id.btnNext)

        var currentIndex = startIndex

        fun loadPhoto(index: Int) {
            val event = photos[index]
            val file = File(event.value)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(event.value)
                imageView.setImageBitmap(bitmap)
                val sta = event.distance.toInt()
                tvCounter.text = "${index + 1}/${photos.size} - STA ${sta/100}+${sta%100}"
            } else {
                Toast.makeText(requireContext(), "File foto tidak ditemukan", Toast.LENGTH_SHORT).show()
            }
            btnPrev.isEnabled = index > 0
            btnNext.isEnabled = index < photos.size - 1
        }

        btnPrev.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                loadPhoto(currentIndex)
            }
        }

        btnNext.setOnClickListener {
            if (currentIndex < photos.size - 1) {
                currentIndex++
                loadPhoto(currentIndex)
            }
        }

        loadPhoto(currentIndex)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.photo_preview_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    // ── SESSION OPTIONS ───────────────────────────────────────────────────

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
                if (file != null) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.export_success))
                        .setMessage(getString(R.string.export_success_message, file.absolutePath))
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                } else {
                    Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
            }
            "csv" -> viewModel.exportSessionToCsv(session.id) { file ->
                if (file != null) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.export_success))
                        .setMessage(getString(R.string.export_success_message, file.absolutePath))
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                } else {
                    Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
            }
            "pdf" -> viewModel.exportSessionToPdf(session.id) { file ->
                if (file != null) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.export_success))
                        .setMessage(getString(R.string.export_success_message, file.absolutePath))
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                } else {
                    Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDelete(session: SurveySession) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_session))
            .setMessage(getString(R.string.delete_session_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.deleteSession(session)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // Helper untuk mendapatkan warna berdasarkan nilai SDI (UI layer)
    private fun getSdiColor(sdi: Int): Int = when (sdi) {
        in 0..20 -> android.graphics.Color.GREEN
        in 21..40 -> android.graphics.Color.parseColor("#8BC34A")
        in 41..60 -> android.graphics.Color.YELLOW
        in 61..80 -> android.graphics.Color.parseColor("#FF9800")
        else -> android.graphics.Color.RED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}