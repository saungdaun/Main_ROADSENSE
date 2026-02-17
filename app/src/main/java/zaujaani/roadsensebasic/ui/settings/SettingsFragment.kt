package zaujaani.roadsensebasic.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import zaujaani.roadsensebasic.databinding.FragmentSettingsBinding
import zaujaani.roadsensebasic.util.PreferencesManager
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var preferencesManager: PreferencesManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadPreferences()
        setupListeners()
    }

    private fun loadPreferences() {
        lifecycleScope.launch {
            preferencesManager.thresholdBaik.collect { value ->
                binding.sliderBaik.value = value
                binding.tvBaik.text = "Baik: ${value}g"
            }
        }
        lifecycleScope.launch {
            preferencesManager.thresholdSedang.collect { value ->
                binding.sliderSedang.value = value
                binding.tvSedang.text = "Sedang: ${value}g"
            }
        }
        lifecycleScope.launch {
            preferencesManager.thresholdRusakRingan.collect { value ->
                binding.sliderRusakRingan.value = value
                binding.tvRusakRingan.text = "Rusak Ringan: ${value}g"
            }
        }
        lifecycleScope.launch {
            preferencesManager.gpsInterval.collect { value ->
                binding.sliderGpsInterval.value = value.toFloat()
                binding.tvGpsInterval.text = "$value ms"
            }
        }
        lifecycleScope.launch {
            preferencesManager.distanceUnit.collect { unit ->
                when (unit) {
                    "km" -> binding.radioKm.isChecked = true
                    "mi" -> binding.radioMi.isChecked = true
                }
            }
        }
        lifecycleScope.launch {
            preferencesManager.theme.collect { theme ->
                when (theme) {
                    "light" -> binding.radioLight.isChecked = true
                    "dark" -> binding.radioDark.isChecked = true
                    else -> binding.radioSystem.isChecked = true
                }
            }
        }
    }

    private fun setupListeners() {
        binding.sliderBaik.addOnChangeListener { slider, value, fromUser ->
            binding.tvBaik.text = "Baik: ${value}g"
            if (fromUser) {
                lifecycleScope.launch {
                    preferencesManager.setThresholdBaik(value)
                }
            }
        }

        binding.sliderSedang.addOnChangeListener { slider, value, fromUser ->
            binding.tvSedang.text = "Sedang: ${value}g"
            if (fromUser) {
                lifecycleScope.launch {
                    preferencesManager.setThresholdSedang(value)
                }
            }
        }

        binding.sliderRusakRingan.addOnChangeListener { slider, value, fromUser ->
            binding.tvRusakRingan.text = "Rusak Ringan: ${value}g"
            if (fromUser) {
                lifecycleScope.launch {
                    preferencesManager.setThresholdRusakRingan(value)
                }
            }
        }

        binding.sliderGpsInterval.addOnChangeListener { slider, value, fromUser ->
            binding.tvGpsInterval.text = "${value.toInt()} ms"
            if (fromUser) {
                lifecycleScope.launch {
                    preferencesManager.setGpsInterval(value.toInt())
                }
            }
        }

        binding.radioGroupUnit.setOnCheckedChangeListener { _, checkedId ->
            val unit = when (checkedId) {
                binding.radioKm.id -> "km"
                binding.radioMi.id -> "mi"
                else -> "km"
            }
            lifecycleScope.launch {
                preferencesManager.setDistanceUnit(unit)
            }
        }

        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                binding.radioLight.id -> "light"
                binding.radioDark.id -> "dark"
                else -> "system"
            }
            lifecycleScope.launch {
                preferencesManager.setTheme(theme)
                // Di sini bisa tambahkan logic untuk mengubah tema aplikasi
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}