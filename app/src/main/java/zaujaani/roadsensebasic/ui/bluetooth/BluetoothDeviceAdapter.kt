package zaujaani.roadsensebasic.ui.bluetooth

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import zaujaani.roadsensebasic.databinding.ItemBluetoothDeviceBinding

class BluetoothDeviceAdapter(
    private val onItemClick: (BluetoothViewModel.BluetoothDeviceInfo) -> Unit
) : ListAdapter<BluetoothViewModel.BluetoothDeviceInfo, BluetoothDeviceAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<BluetoothViewModel.BluetoothDeviceInfo>() {
                override fun areItemsTheSame(
                    oldItem: BluetoothViewModel.BluetoothDeviceInfo,
                    newItem: BluetoothViewModel.BluetoothDeviceInfo
                ): Boolean = oldItem.address == newItem.address

                override fun areContentsTheSame(
                    oldItem: BluetoothViewModel.BluetoothDeviceInfo,
                    newItem: BluetoothViewModel.BluetoothDeviceInfo
                ): Boolean = oldItem == newItem
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBluetoothDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemBluetoothDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BluetoothViewModel.BluetoothDeviceInfo) {
            binding.tvDeviceName.text = item.name.ifBlank { "Unknown" }
            binding.tvDeviceAddress.text = item.address
            binding.tvBonded.visibility = if (item.isBonded) View.VISIBLE else View.GONE
            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}