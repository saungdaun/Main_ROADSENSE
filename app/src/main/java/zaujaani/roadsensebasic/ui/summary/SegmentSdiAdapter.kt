package zaujaani.roadsensebasic.ui.summary

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import zaujaani.roadsensebasic.data.local.entity.SegmentSdi
import zaujaani.roadsensebasic.databinding.ItemSegmentSdiBinding

class SegmentSdiAdapter(
    private val onItemClick: (SegmentSdi) -> Unit
) : RecyclerView.Adapter<SegmentSdiAdapter.ViewHolder>() {

    private var segments = listOf<SegmentSdi>()

    fun submitList(list: List<SegmentSdi>) {
        segments = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSegmentSdiBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(segments[position])
    }

    override fun getItemCount() = segments.size

    inner class ViewHolder(
        private val binding: ItemSegmentSdiBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(segment: SegmentSdi) {
            binding.tvSta.text = "${segment.startSta} – ${segment.endSta}"
            binding.tvSdiScore.text = "SDI ${segment.sdiScore}"
            binding.tvDistressCount.text = "${segment.distressCount} kerusakan"

            val color = getSdiColor(segment.sdiScore)
            binding.viewColorIndicator.setBackgroundColor(color)

            binding.root.setOnClickListener { onItemClick(segment) }
        }

        private fun getSdiColor(sdi: Int): Int = when (sdi) {
            in 0..20 -> Color.GREEN
            in 21..40 -> Color.parseColor("#8BC34A")
            in 41..60 -> Color.YELLOW
            in 61..80 -> Color.parseColor("#FF9800")
            else -> Color.RED
        }
    }
}