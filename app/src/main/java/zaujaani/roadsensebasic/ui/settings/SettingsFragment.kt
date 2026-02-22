package zaujaani.roadsensebasic.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.databinding.FragmentSettingsBinding
import zaujaani.roadsensebasic.util.PreferencesManager
import java.util.Locale
import javax.inject.Inject

/**
 * SettingsFragment v2 — Kalibrasi + Bahasa
 *
 * NEW: Language selector (English / Bahasa Indonesia)
 *   - Menggunakan AppCompatDelegate.setApplicationLocales() — AppCompat 1.6+
 *   - Tidak perlu restart Activity, tidak perlu recreate()
 *   - Pilihan disimpan di PreferencesManager (DataStore + SharedPrefs cache)
 *   - Default: English
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var preferencesManager: PreferencesManager

    // Prevent listener loop while loading saved values
    private var isLoadingPrefs = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadPreferences()
        setupListeners()
    }

    // ── Load saved preferences ────────────────────────────────────────────

    private fun loadPreferences() {
        isLoadingPrefs = true

        lifecycleScope.launch {
            preferencesManager.thresholdBaik.collect { value ->
                if (!isLoadingPrefs) return@collect
                binding.sliderBaik.value = value.coerceIn(0.1f, 0.5f)
                binding.tvBaik.text = getString(R.string.threshold_baik_label,
                    String.format(Locale.US, "%.2f", value))
                isLoadingPrefs = false
            }
        }
        lifecycleScope.launch {
            preferencesManager.thresholdSedang.collect { value ->
                binding.sliderSedang.value = value.coerceIn(0.4f, 0.8f)
                binding.tvSedang.text = getString(R.string.threshold_sedang_label,
                    String.format(Locale.US, "%.2f", value))
            }
        }
        lifecycleScope.launch {
            preferencesManager.thresholdRusakRingan.collect { value ->
                binding.sliderRusakRingan.value = value.coerceIn(0.8f, 1.5f)
                binding.tvRusakRingan.text = getString(R.string.threshold_rusak_label,
                    String.format(Locale.US, "%.2f", value))
            }
        }
        lifecycleScope.launch {
            preferencesManager.gpsInterval.collect { value ->
                binding.sliderGpsInterval.value = value.toFloat().coerceIn(500f, 5000f)
                binding.tvGpsInterval.text = getString(R.string.gps_interval_label, value)
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
                    "dark"  -> binding.radioDark.isChecked  = true
                    else    -> binding.radioSystem.isChecked = true
                }
            }
        }

        // Load language preference
        lifecycleScope.launch {
            preferencesManager.language.collect { lang ->
                // Suppress listener while loading
                binding.radioGroupLanguage.setOnCheckedChangeListener(null)
                when (lang) {
                    PreferencesManager.LANG_INDONESIAN -> binding.radioLangId.isChecked = true
                    else                               -> binding.radioLangEn.isChecked  = true
                }
                // Re-attach listener after setting value
                setupLanguageListener()
            }
        }
    }

    // ── Listeners ─────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.sliderBaik.addOnChangeListener { _, value, fromUser ->
            binding.tvBaik.text = getString(R.string.threshold_baik_label,
                String.format(Locale.US, "%.2f", value))
            if (fromUser) lifecycleScope.launch { preferencesManager.setThresholdBaik(value) }
        }

        binding.sliderSedang.addOnChangeListener { _, value, fromUser ->
            binding.tvSedang.text = getString(R.string.threshold_sedang_label,
                String.format(Locale.US, "%.2f", value))
            if (fromUser) lifecycleScope.launch { preferencesManager.setThresholdSedang(value) }
        }

        binding.sliderRusakRingan.addOnChangeListener { _, value, fromUser ->
            binding.tvRusakRingan.text = getString(R.string.threshold_rusak_label,
                String.format(Locale.US, "%.2f", value))
            if (fromUser) lifecycleScope.launch { preferencesManager.setThresholdRusakRingan(value) }
        }

        binding.sliderGpsInterval.addOnChangeListener { _, value, fromUser ->
            binding.tvGpsInterval.text = getString(R.string.gps_interval_label, value.toInt())
            if (fromUser) lifecycleScope.launch { preferencesManager.setGpsInterval(value.toInt()) }
        }

        binding.radioGroupUnit.setOnCheckedChangeListener { _, checkedId ->
            val unit = if (checkedId == binding.radioMi.id) "mi" else "km"
            lifecycleScope.launch { preferencesManager.setDistanceUnit(unit) }
        }

        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                binding.radioLight.id -> "light"
                binding.radioDark.id  -> "dark"
                else                  -> "system"
            }
            val mode = when (theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
                else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            lifecycleScope.launch {
                preferencesManager.setTheme(theme)
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }

        setupLanguageListener()
    }

    private fun setupLanguageListener() {
        binding.radioGroupLanguage.setOnCheckedChangeListener { _, checkedId ->
            val langTag = when (checkedId) {
                binding.radioLangId.id -> PreferencesManager.LANG_INDONESIAN
                else -> PreferencesManager.LANG_ENGLISH
            }

            viewLifecycleOwner.lifecycleScope.launch {
                preferencesManager.setLanguage(langTag)

                val localeList = LocaleListCompat.forLanguageTags(langTag)
                AppCompatDelegate.setApplicationLocales(localeList)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}