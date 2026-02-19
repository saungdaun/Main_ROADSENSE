package zaujaani.roadsensebasic.ui.summary

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
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

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        adapter = SessionAdapter(
            onItemClick = { item -> showSessionDetail(item.session.id) },
            onDetailClick = { item -> showSessionOptions(item.session) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadSessions()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Observers
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Session Detail Dialog
    // ─────────────────────────────────────────────────────────────────────────

    private fun showSessionDetail(sessionId: Long) {
        viewModel.loadSessionDetail(sessionId)
    }

    private fun showSessionDetailDialog(detail: SessionDetailUi) {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

        // Header info
        sb.appendLine(getString(R.string.detail_date,
            dateFormat.format(Date(detail.session.startTime))))
        if (detail.session.surveyorName.isNotBlank()) {
            sb.appendLine(getString(R.string.detail_surveyor, detail.session.surveyorName))
        }
        sb.appendLine(getString(R.string.detail_device, detail.session.deviceModel))
        if (detail.session.roadName.isNotBlank()) {
            sb.appendLine("🛣 ${detail.session.roadName}")
        }

        // Statistik
        val totalDistText = formatDistance(detail.totalDistance)
        sb.appendLine(getString(R.string.detail_total_distance, totalDistText))
        sb.appendLine(getString(R.string.detail_duration, detail.durationMinutes))
        sb.appendLine(getString(R.string.detail_avg_confidence, detail.avgConfidence))
        sb.appendLine(getString(R.string.detail_photos, detail.photoCount))
        sb.appendLine(getString(R.string.detail_audios, detail.audioCount))
        sb.appendLine()

        // Distribusi kondisi jalan
        sb.appendLine(getString(R.string.detail_condition_header))
        listOf(
            Constants.CONDITION_BAIK,
            Constants.CONDITION_SEDANG,
            Constants.CONDITION_RUSAK_RINGAN,
            Constants.CONDITION_RUSAK_BERAT
        ).forEach { cond ->
            val length = detail.conditionDistribution[cond] ?: 0.0
            if (length > 0) {
                val pct = if (detail.totalDistance > 0)
                    (length / detail.totalDistance * 100).toInt() else 0
                sb.appendLine("  $cond: ${formatDistance(length)} ($pct%)")
            }
        }
        sb.appendLine()

        // Distribusi permukaan
        if (detail.surfaceDistribution.isNotEmpty()) {
            sb.appendLine(getString(R.string.detail_surface_header))
            detail.surfaceDistribution.forEach { (surface, length) ->
                val pct = if (detail.totalDistance > 0)
                    (length / detail.totalDistance * 100).toInt() else 0
                sb.appendLine("  $surface: ${formatDistance(length)} ($pct%)")
            }
            sb.appendLine()
        }

        // Titik penting (events)
        if (detail.events.isNotEmpty()) {
            sb.appendLine("📌 Titik Penting:")
            detail.events.forEach { event ->
                val sta = event.distance.toInt()
                val staStr = "STA ${sta / 100}+${String.format(Locale.getDefault(), "%02d", sta % 100)}"
                val typeStr = when (event.eventType) {
                    EventType.CONDITION_CHANGE -> "🔵 Kondisi: ${event.value}"
                    EventType.SURFACE_CHANGE   -> "🟢 Permukaan: ${event.value}"
                    EventType.PHOTO            -> "📷 Foto"
                    EventType.VOICE            -> "🎤 Rekaman"
                }
                sb.appendLine("  $staStr — $typeStr")
                if (!event.notes.isNullOrBlank()) {
                    sb.appendLine("     📝 ${event.notes}")
                }
            }
        }

        val photoEvents = detail.events.filter { it.eventType == EventType.PHOTO }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.detail_title))
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.export_gpx)) { _, _ ->
                exportSession(detail.session, "gpx")
            }
            .setNeutralButton(getString(R.string.export_csv)) { _, _ ->
                exportSession(detail.session, "csv")
            }
            .setNegativeButton(getString(R.string.view_photos)) { _, _ ->
                if (photoEvents.isNotEmpty()) {
                    showPhotoSlider(photoEvents, 0)
                } else {
                    Toast.makeText(requireContext(),
                        getString(R.string.no_photos), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session Options & Delete
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Export
    // ─────────────────────────────────────────────────────────────────────────

    private fun exportSession(session: SurveySession, format: String) {
        when (format) {
            "gpx" -> viewModel.exportSessionToGpx(session.id) { file ->
                showExportResult(file)
            }
            "csv" -> viewModel.exportSessionToCsv(session.id) { file ->
                showExportResult(file)
            }
        }
    }

    private fun showExportResult(file: File?) {
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

    // ─────────────────────────────────────────────────────────────────────────
    // Photo Slider
    // ─────────────────────────────────────────────────────────────────────────

    private fun showPhotoSlider(photos: List<RoadEvent>, startIndex: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_photo_slider, null)
        val imageView   = dialogView.findViewById<ImageView>(R.id.ivPhoto)
        val tvCounter   = dialogView.findViewById<TextView>(R.id.tvCounter)
        val btnPrev     = dialogView.findViewById<MaterialButton>(R.id.btnPrev)
        val btnNext     = dialogView.findViewById<MaterialButton>(R.id.btnNext)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)

        var currentIndex = startIndex
        var currentBitmap: Bitmap? = null

        fun loadPhoto(index: Int) {
            val event = photos[index]
            val file  = File(event.value)

            // Update counter & STA
            val sta = event.distance.toInt()
            tvCounter.text = "${index + 1}/${photos.size} — STA ${sta / 100}+" +
                    String.format(Locale.getDefault(), "%02d", sta % 100)

            btnPrev.isEnabled = index > 0
            btnNext.isEnabled = index < photos.size - 1

            if (!file.exists()) {
                imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                Toast.makeText(requireContext(),
                    getString(R.string.photo_file_not_found), Toast.LENGTH_SHORT).show()
                return
            }

            // Load gambar di IO thread — hindari ANR & OOM
            progressBar.visibility = View.VISIBLE
            imageView.setImageDrawable(null)

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val bitmap = try {
                    decodeSampledBitmap(file.absolutePath, 900, 675)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to decode photo: ${file.absolutePath}")
                    null
                }
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext // fragment sudah destroyed
                    progressBar.visibility = View.GONE
                    if (bitmap != null) {
                        currentBitmap?.recycle()
                        currentBitmap = bitmap
                        imageView.setImageBitmap(bitmap)
                    } else {
                        imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                        Toast.makeText(requireContext(),
                            getString(R.string.photo_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnPrev.setOnClickListener {
            if (currentIndex > 0) { currentIndex--; loadPhoto(currentIndex) }
        }
        btnNext.setOnClickListener {
            if (currentIndex < photos.size - 1) { currentIndex++; loadPhoto(currentIndex) }
        }

        loadPhoto(currentIndex)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.photo_preview_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close)) { _, _ ->
                currentBitmap?.recycle()
                currentBitmap = null
            }
            .setOnDismissListener {
                currentBitmap?.recycle()
                currentBitmap = null
            }
            .show()
    }

    /**
     * Decode bitmap dengan subsampling agar tidak OOM.
     * Pass 1: baca dimensi saja. Pass 2: decode dengan inSampleSize.
     */
    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOpts)

        var sampleSize = 1
        val rawH = boundsOpts.outHeight
        val rawW = boundsOpts.outWidth
        if (rawH > reqHeight || rawW > reqWidth) {
            val halfH = rawH / 2
            val halfW = rawW / 2
            while (halfH / sampleSize >= reqHeight && halfW / sampleSize >= reqWidth) {
                sampleSize *= 2
            }
        }

        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            // RGB_565 hemat ~50% RAM dibanding ARGB_8888 — cukup untuk preview foto
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            getString(R.string.distance_m_format, meters.toInt())
        } else {
            getString(R.string.distance_km_format, meters / 1000.0)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}