package zaujaani.roadsensebasic.ui.summary

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.repository.SessionWithCount
import zaujaani.roadsensebasic.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Locale

class SessionAdapter(
    private val onItemClick: (SessionWithCount) -> Unit,
    private val onDetailClick: (SessionWithCount) -> Unit  // tambahan
) : RecyclerView.Adapter<SessionAdapter.SessionViewHolder>() {

    private var items = listOf<SessionWithCount>()

    fun submitList(list: List<SessionWithCount>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding, onItemClick, onDetailClick)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class SessionViewHolder(
        private val binding: ItemSessionBinding,
        private val onItemClick: (SessionWithCount) -> Unit,
        private val onDetailClick: (SessionWithCount) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SessionWithCount) {
            val session = item.session
            val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
            binding.tvDate.text = dateFormat.format(session.startTime)

            binding.tvDevice.text = binding.root.context.getString(R.string.device_model, session.deviceModel)

            binding.tvTotalDistance.text = if (session.totalDistance < 1000) {
                binding.root.context.getString(R.string.distance_m, session.totalDistance.toInt())
            } else {
                binding.root.context.getString(R.string.distance_km, session.totalDistance / 1000)
            }

            binding.tvSegmentCount.text = item.segmentCount.toString()

            binding.root.setOnClickListener {
                onItemClick(item)
            }

            binding.btnViewDetails.setOnClickListener {
                onDetailClick(item)
            }
        }
    }
}