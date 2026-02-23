package zaujaani.roadsensebasic.ui.summary

import android.graphics.Color
import androidx.core.graphics.toColorInt
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.SurveyMode
import zaujaani.roadsensebasic.data.repository.SessionWithCount
import zaujaani.roadsensebasic.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SessionAdapter v2 — Diferensiasi visual per survey mode.
 *
 * Tiap card menampilkan:
 * - Strip warna kiri  : UMUM=Biru, SDI=Oranye, PCI=Hijau
 * - Badge mode        : [SURVEI UMUM] / [SURVEI SDI] / [SURVEI PCI]
 * - Metric chips      : Jarak | Skor mode | Event/Segmen
 *
 * Skor mode:
 * - GENERAL : "–" (tidak ada skor indeks)
 * - SDI     : Nilai SDI 0–100 dengan warna hijau/kuning/merah
 * - PCI     : Nilai PCI 0–100 dengan warna hijau/kuning/merah
 */
class SessionAdapter(
    private val onItemClick: (SessionWithCount) -> Unit,
    private val onDetailClick: (SessionWithCount) -> Unit
) : ListAdapter<SessionWithCount, SessionAdapter.SessionViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SessionWithCount>() {
            override fun areItemsTheSame(old: SessionWithCount, new: SessionWithCount) =
                old.session.id == new.session.id

            override fun areContentsTheSame(old: SessionWithCount, new: SessionWithCount) =
                old == new
        }

        // ── Warna strip kiri per mode ──────────────────────────────────────
        // GENERAL : Biru primer  (dokumentasi jalan umum)
        // SDI     : Oranye      (kerusakan struktural — perlu perhatian)
        // PCI     : Hijau tua   (indeks kondisi perkerasan — ASTM)
        private const val COLOR_GENERAL = "#1565C0"
        private const val COLOR_SDI     = "#E65100"
        private const val COLOR_PCI     = "#2E7D32"

        // ── Warna skor SDI/PCI (kondisi jalan) ────────────────────────────
        private const val COLOR_SCORE_GOOD   = "#2E7D32"   // 70–100: Baik
        private const val COLOR_SCORE_MEDIUM = "#F57F17"   // 40–69 : Sedang
        private const val COLOR_SCORE_POOR   = "#B71C1C"   // 0–39  : Buruk
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding, onItemClick, onDetailClick)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SessionViewHolder(
        private val binding: ItemSessionBinding,
        private val onItemClick: (SessionWithCount) -> Unit,
        private val onDetailClick: (SessionWithCount) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())

        fun bind(item: SessionWithCount) {
            val session = item.session
            val mode    = session.mode

            // ── 1. Strip warna kiri ──────────────────────────────────────
            val stripColor = when (mode) {
                SurveyMode.GENERAL -> COLOR_GENERAL.toColorInt()
                SurveyMode.SDI     -> COLOR_SDI.toColorInt()
                SurveyMode.PCI     -> COLOR_PCI.toColorInt()
            }
            binding.viewModeStrip.setBackgroundColor(stripColor)

            // ── 2. Badge mode ────────────────────────────────────────────
            val (badgeText, badgeColor) = when (mode) {
                SurveyMode.GENERAL -> "SURVEI UMUM" to COLOR_GENERAL
                SurveyMode.SDI     -> "SURVEI SDI"  to COLOR_SDI
                SurveyMode.PCI     -> "SURVEI PCI"  to COLOR_PCI
            }
            binding.tvModeBadge.text = badgeText
            binding.tvModeBadge.backgroundTintList =
                android.content.res.ColorStateList.valueOf(badgeColor.toColorInt())

            // ── 3. Tanggal ───────────────────────────────────────────────
            binding.tvDate.text = dateFormat.format(Date(session.startTime))

            // ── 4. Nama jalan ────────────────────────────────────────────
            if (session.roadName.isNotBlank()) {
                binding.tvRoadName.text = "🛣  ${session.roadName}"
                binding.tvRoadName.visibility = android.view.View.VISIBLE
            } else {
                binding.tvRoadName.text = "🛣  Tidak ada nama jalan"
                binding.tvRoadName.visibility = android.view.View.VISIBLE
            }

            // ── 5. Surveyor ──────────────────────────────────────────────
            if (session.surveyorName.isNotBlank()) {
                binding.tvSurveyor.text = "👤  ${session.surveyorName}"
                binding.tvSurveyor.visibility = android.view.View.VISIBLE
            } else {
                binding.tvSurveyor.visibility = android.view.View.GONE
            }

            // ── 6. Metric chip 1: Jarak total ───────────────────────────
            binding.tvDistanceValue.text = if (session.totalDistance < 1000) {
                "${session.totalDistance.toInt()} m"
            } else {
                "${"%.1f".format(session.totalDistance / 1000.0)} km"
            }

            // ── 7. Metric chip 2: Skor per mode ─────────────────────────
            when (mode) {
                SurveyMode.GENERAL -> {
                    // GENERAL tidak punya skor indeks
                    binding.tvScoreValue.text = "—"
                    binding.tvScoreLabel.text = "Skor"
                    binding.tvScoreValue.setTextColor("#9E9E9E".toColorInt())
                }

                SurveyMode.SDI -> {
                    val sdi = session.avgSdi
                    binding.tvScoreValue.text = if (sdi > 0) sdi.toString() else "—"
                    binding.tvScoreLabel.text = "SDI"
                    binding.tvScoreValue.setTextColor(
                        if (sdi <= 0) "#9E9E9E".toColorInt()
                        else scoreColor(sdi, higherIsBetter = true)
                    )
                }

                SurveyMode.PCI -> {
                    val pci = session.avgPci
                    binding.tvScoreValue.text = if (pci >= 0) pci.toString() else "—"
                    binding.tvScoreLabel.text = "PCI"
                    binding.tvScoreValue.setTextColor(
                        if (pci < 0) "#9E9E9E".toColorInt()
                        else scoreColor(pci, higherIsBetter = true)
                    )
                }
            }

            // ── 8. Metric chip 3: Event / segmen ────────────────────────
            binding.tvEventValue.text = item.eventCount.toString()
            binding.tvEventLabel.text = when (mode) {
                SurveyMode.GENERAL -> "Foto/Event"
                SurveyMode.SDI     -> "Kerusakan"
                SurveyMode.PCI     -> "Distress"
            }

            // ── 9. Click handlers ────────────────────────────────────────
            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnViewDetails.setOnClickListener { onDetailClick(item) }
        }

        /**
         * Warna teks skor berdasarkan nilai 0–100.
         * higherIsBetter = true → tinggi = hijau (PCI, SDI kondisi jalan)
         */
        private fun scoreColor(score: Int, higherIsBetter: Boolean): Int {
            val isGood   = if (higherIsBetter) score >= 70 else score <= 30
            val isMedium = if (higherIsBetter) score in 40..69 else score in 31..60
            return when {
                isGood   -> COLOR_SCORE_GOOD.toColorInt()
                isMedium -> COLOR_SCORE_MEDIUM.toColorInt()
                else     -> COLOR_SCORE_POOR.toColorInt()
            }
        }
    }
}