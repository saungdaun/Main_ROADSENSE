package zaujaani.roadsensebasic.ui.summary

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.repository.SessionWithCount
import zaujaani.roadsensebasic.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter untuk daftar sesi survey dengan DiffUtil untuk update efisien.
 */
class SessionAdapter(
    private val onItemClick: (SessionWithCount) -> Unit,
    private val onDetailClick: (SessionWithCount) -> Unit
) : ListAdapter<SessionWithCount, SessionAdapter.SessionViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SessionWithCount>() {
            override fun areItemsTheSame(old: SessionWithCount, new: SessionWithCount): Boolean {
                return old.session.id == new.session.id
            }

            override fun areContentsTheSame(old: SessionWithCount, new: SessionWithCount): Boolean {
                return old == new
            }
        }
    }

    // Tidak perlu mendefinisikan ulang submitList, gunakan dari ListAdapter

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
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

        private val dateFormat = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())

        fun bind(item: SessionWithCount) {
            val session = item.session
            val ctx = binding.root.context

            // Tanggal
            binding.tvDate.text = dateFormat.format(Date(session.startTime))

            // Device
            binding.tvDevice.text = ctx.getString(R.string.device_model, session.deviceModel)

            // Jarak total
            binding.tvTotalDistance.text = if (session.totalDistance < 1000) {
                ctx.getString(R.string.distance_m_format, session.totalDistance.toInt())
            } else {
                ctx.getString(R.string.distance_km_format, session.totalDistance / 1000.0)
            }

            // Jumlah segmen
            binding.tvSegmentCount.text = item.segmentCount.toString()

            // Nama surveyor + jalan (jika ada)
            if (session.roadName.isNotBlank() || session.surveyorName.isNotBlank()) {
                val info = buildString {
                    if (session.roadName.isNotBlank()) append("🛣 ${session.roadName}")
                    if (session.roadName.isNotBlank() && session.surveyorName.isNotBlank()) append("  ")
                    if (session.surveyorName.isNotBlank()) append("👤 ${session.surveyorName}")
                }
                binding.tvDevice.text = info
            }

            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnViewDetails.setOnClickListener { onDetailClick(item) }
        }
    }
}