package zaujaani.roadsensebasic.ui.summary

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.EventType
import zaujaani.roadsensebasic.data.local.entity.RoadEvent
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.databinding.FragmentSummaryBinding
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
            showSessionDetailDialog(detail)
        }
    }

    private fun showSessionDetail(sessionId: Long) {
        viewModel.loadSessionDetail(sessionId)
    }

    private fun showSessionDetailDialog(detail: SessionDetailUi) {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

        // Header info
        sb.appendLine(getString(R.string.detail_date, dateFormat.format(Date(detail.session.startTime))))
        sb.appendLine(getString(R.string.detail_device, detail.session.deviceModel))
        if (detail.session.surveyorName.isNotBlank()) {
            sb.appendLine(getString(R.string.detail_surveyor, detail.session.surveyorName))
        }
        if (detail.session.roadName.isNotBlank()) {
            sb.appendLine("🛣 ${detail.session.roadName}")
        }

        // GPS info
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

        // Distribusi kondisi jalan
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

        // Distribusi permukaan
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

        // Detail per event (foto, voice, perubahan kondisi/permukaan)
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

        // Buat dialog dengan tombol Lihat Foto
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.detail_title))
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.export_gpx)) { _, _ ->
                exportSession(detail.session, "gpx")
            }
            .setNeutralButton("Lihat Foto") { _, _ ->
                showPhotoListDialog(detail.events)
            }
            .setNegativeButton(getString(R.string.ok), null)
            .show()
    }

    private fun showPhotoListDialog(events: List<RoadEvent>) {
        val photoEvents = events.filter { it.eventType == EventType.PHOTO }
        if (photoEvents.isEmpty()) {
            Toast.makeText(requireContext(), "Tidak ada foto", Toast.LENGTH_SHORT).show()
            return
        }

        val items = photoEvents.map { event ->
            val sta = event.distance.toInt()
            val staStr = "STA ${sta/100}+${sta%100}  ${event.value.substringAfterLast('/')}"
            staStr
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Daftar Foto")
            .setItems(items) { _, which ->
                val selectedEvent = photoEvents[which]
                showPhotoPreview(selectedEvent.value)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showPhotoPreview(photoPath: String) {
        val file = File(photoPath)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "File foto tidak ditemukan", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = BitmapFactory.decodeFile(photoPath)
        val imageView = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Preview Foto")
            .setView(imageView)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun showSessionOptions(session: SurveySession) {
        val items = arrayOf(
            getString(R.string.view_details),
            getString(R.string.export_gpx),
            getString(R.string.export_csv),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.session_options))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSessionDetail(session.id)
                    1 -> exportSession(session, "gpx")
                    2 -> exportSession(session, "csv")
                    3 -> confirmDelete(session)
                }
            }
            .show()
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
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}