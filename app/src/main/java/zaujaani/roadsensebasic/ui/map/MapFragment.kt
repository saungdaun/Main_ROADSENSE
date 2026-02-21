package zaujaani.roadsensebasic.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.charts.LineChart
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import timber.log.Timber
import zaujaani.roadsensebasic.BuildConfig
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.Condition
import zaujaani.roadsensebasic.data.local.entity.Surface
import zaujaani.roadsensebasic.data.local.entity.SurveyMode
import zaujaani.roadsensebasic.databinding.FragmentMapBinding
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.ui.distress.DistressBottomSheet
import zaujaani.roadsensebasic.util.MapRenderer
import zaujaani.roadsensebasic.util.MediaManager
import zaujaani.roadsensebasic.util.VibrationChartController
import javax.inject.Inject

@AndroidEntryPoint
class MapFragment : Fragment() {

    @Inject
    lateinit var sensorGateway: SensorGateway

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels()

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var mapView: MapView
    private lateinit var chart: LineChart
    private lateinit var fabSurvey: FloatingActionButton
    private lateinit var fabStop: FloatingActionButton
    private lateinit var fabToggleChart: FloatingActionButton
    private lateinit var fabCamera: FloatingActionButton
    private lateinit var fabVoice: FloatingActionButton
    private lateinit var fabCondition: FloatingActionButton
    private lateinit var fabSurface: FloatingActionButton
    private lateinit var fabToggleOrientation: FloatingActionButton
    private lateinit var fabAddDistress: FloatingActionButton

    // ── Helpers ────────────────────────────────────────────────────────────
    private lateinit var mediaManager: MediaManager
    private lateinit var mapRenderer: MapRenderer
    private lateinit var chartController: VibrationChartController

    // ── Colors ─────────────────────────────────────────────────────────────
    private val colorBaik        by lazy { ContextCompat.getColor(requireContext(), R.color.green) }
    private val colorSedang      by lazy { ContextCompat.getColor(requireContext(), R.color.yellow) }
    private val colorRusakRingan by lazy { ContextCompat.getColor(requireContext(), R.color.orange) }
    private val colorRusakBerat  by lazy { ContextCompat.getColor(requireContext(), R.color.red) }
    private val colorDefault     by lazy { ContextCompat.getColor(requireContext(), R.color.primary) }

    // ── State ──────────────────────────────────────────────────────────────
    private var isChartVisible       = true
    private var isFollowingGPS       = true
    private var isOrientationEnabled = true
    private var snackbarVoice: Snackbar? = null

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Configuration.getInstance().apply {
            load(
                requireContext(),
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
            )
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
        }

        bindViews()
        initHelpers()
        setupMap()
        setupChart()
        setupClickListeners()
        observeViewModel()
        observeDistanceTrigger()
        restoreSurveyState()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        sensorGateway.startListening()
        if (hasLocationPermission()) viewModel.startLocationTracking()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        // Sensor & GPS tetap aktif jika survey sedang berjalan
        if (!viewModel.isSurveying.value) {
            sensorGateway.stopListening()
            viewModel.stopLocationTracking()
        }
        // Recording voice tetap berjalan saat pindah fragment —
        // snackbar akan di-dismiss di onDestroyView, rekaman tidak terpotong
    }

    override fun onDestroyView() {
        super.onDestroyView()
        snackbarVoice?.dismiss()
        snackbarVoice = null
        mapView.overlays.clear()
        mediaManager.release()
        if (!viewModel.isSurveying.value) {
            sensorGateway.stopListening()
            viewModel.stopLocationTracking()
        }
        _binding = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // INIT
    // ══════════════════════════════════════════════════════════════════════

    private fun bindViews() {
        mapView              = binding.mapView
        chart                = binding.chartVibration
        fabSurvey            = binding.fabSurvey
        fabStop              = binding.fabStop
        fabToggleChart       = binding.fabToggleChart
        fabCamera            = binding.fabCamera
        fabVoice             = binding.fabVoice
        fabCondition         = binding.fabCondition
        fabSurface           = binding.fabSurface
        fabToggleOrientation = binding.fabToggleOrientation
        fabAddDistress       = binding.fabAddDistress
    }

    private fun initHelpers() {
        // MediaManager dengan metadataProvider → watermark foto selalu pakai data real-time
        mediaManager = MediaManager(
            fragment       = this,
            lifecycleScope = lifecycleScope,
            onPhotoTaken   = { path, meta ->
                viewModel.recordPhoto(path, meta.distanceMeters, meta.latitude, meta.longitude)
            },
            onVoiceRecorded = { path ->
                viewModel.recordVoice(path)
            },
            metadataProvider = {
                MediaManager.PhotoMetadata(
                    distanceMeters = viewModel.getCurrentDistance(),
                    latitude       = viewModel.getCurrentLat(),
                    longitude      = viewModel.getCurrentLng(),
                    roadName       = viewModel.getCurrentRoadName(),
                    surveyMode     = viewModel.mode.value.name
                )
            }
        )

        mapRenderer = MapRenderer(mapView).apply {
            initPolyline(colorDefault)
        }

        chartController = VibrationChartController(chart)
    }

    private fun setupMap() {
        mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(18.0)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setTilesScaledToDpi(true)
        }
    }

    private fun setupChart() {
        chartController.setupChart(
            thresholdBaik        = viewModel.thresholdBaik.value,
            thresholdSedang      = viewModel.thresholdSedang.value,
            thresholdRusakRingan = viewModel.thresholdRusakRingan.value,
            colorBaik            = colorBaik,
            colorSedang          = colorSedang,
            colorRusakBerat      = colorRusakBerat
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // CLICK LISTENERS
    // ══════════════════════════════════════════════════════════════════════

    private fun setupClickListeners() {

        fabSurvey.setOnClickListener { handleSurveyButtonClick() }
        fabStop.setOnClickListener   { showStopSurveyConfirmation() }

        fabToggleChart.setOnClickListener {
            isChartVisible = !isChartVisible
            binding.chartContainer.visibility = if (isChartVisible) View.VISIBLE else View.GONE
            fabToggleChart.setImageResource(
                if (isChartVisible) R.drawable.ic_chart else R.drawable.ic_chart_off
            )
        }

        fabCamera.setOnClickListener {
            if (viewModel.isSurveying.value && !viewModel.isPaused.value) {
                mediaManager.openCamera()
            } else {
                showToast(getString(R.string.survey_not_active))
            }
        }

        // FIX: toggle start/stop recording — sebelumnya always start
        fabVoice.setOnClickListener {
            if (viewModel.isSurveying.value && !viewModel.isPaused.value) {
                mediaManager.toggleVoiceRecording()
            } else {
                showToast(getString(R.string.survey_not_active))
            }
        }

        fabCondition.setOnClickListener { showConditionPicker() }
        fabSurface.setOnClickListener   { showSurfacePicker() }

        fabToggleOrientation.setOnClickListener {
            isOrientationEnabled = !isOrientationEnabled
            mapRenderer.setOrientationEnabled(isOrientationEnabled)
            showToast(
                if (isOrientationEnabled) getString(R.string.orientation_enabled)
                else getString(R.string.orientation_disabled)
            )
        }

        fabAddDistress.setOnClickListener {
            if (viewModel.isSurveying.value && !viewModel.isPaused.value
                && viewModel.mode.value == SurveyMode.SDI
            ) {
                DistressBottomSheet().show(childFragmentManager, "DistressBottomSheet")
            } else {
                showToast(getString(R.string.sdi_mode_required))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SURVEY DIALOGS
    // ══════════════════════════════════════════════════════════════════════

    private fun handleSurveyButtonClick() {
        when {
            !viewModel.isSurveying.value -> showStartSurveyDialog()
            viewModel.isPaused.value     -> viewModel.resumeSurvey()
            else                          -> viewModel.pauseSurvey()
        }
    }

    private fun showStartSurveyDialog() {
        val dialogView   = layoutInflater.inflate(R.layout.dialog_start_survey, null)
        val etSurveyor   = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSurveyorName)
        val etRoadName   = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRoadName)
        val radioGeneral = dialogView.findViewById<RadioButton>(R.id.radioGeneral)
        val radioSdi     = dialogView.findViewById<RadioButton>(R.id.radioSdi)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.start_survey))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.start)) { _, _ ->
                val surveyor = etSurveyor?.text?.toString()?.trim() ?: ""
                val roadName = etRoadName?.text?.toString()?.trim() ?: ""
                val mode     = if (radioSdi.isChecked) SurveyMode.SDI else SurveyMode.GENERAL

                if (surveyor.isBlank()) {
                    showToast(getString(R.string.surveyor_name_required))
                    return@setPositiveButton
                }
                viewModel.startSurvey(surveyor, roadName, mode)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showConditionPicker() {
        val conditions   = Condition.entries.toTypedArray()
        val currentIndex = conditions.indexOf(viewModel.currentCondition.value).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.pick_condition_title))
            .setSingleChoiceItems(
                conditions.map { it.name }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                val selected = conditions[which]
                if (!viewModel.checkConditionConsistency(selected)) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.warning_title))
                        .setMessage(getString(R.string.condition_mismatch_message, selected.name))
                        .setPositiveButton(getString(R.string.ok)) { _, _ ->
                            viewModel.recordCondition(selected)
                            dialog.dismiss()
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                } else {
                    viewModel.recordCondition(selected)
                    dialog.dismiss()
                    showToast(getString(R.string.condition_selected, selected.name))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSurfacePicker() {
        val surfaces     = Surface.entries.toTypedArray()
        val currentIndex = surfaces.indexOf(viewModel.currentSurface.value).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.pick_surface_title))
            .setSingleChoiceItems(
                surfaces.map { it.name }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                val selected = surfaces[which]
                viewModel.recordSurface(selected)
                dialog.dismiss()
                showToast(getString(R.string.surface_selected, selected.name))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showStopSurveyConfirmation() {
        viewModel.pauseSurvey()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.stop_survey))
            .setMessage(getString(R.string.stop_survey_confirm))
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                viewModel.stopSurveyAndSave()
                showSurveyFABs(false)
                mapRenderer.clearOverlays(colorDefault)
                findNavController().navigate(R.id.summaryFragment)
            }
            .setNegativeButton(getString(R.string.discard)) { _, _ ->
                viewModel.stopSurveyAndDiscard()
                showSurveyFABs(false)
                mapRenderer.clearOverlays(colorDefault)
            }
            .setNeutralButton(getString(R.string.resume)) { _, _ ->
                viewModel.resumeSurvey()
            }
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════
    // OBSERVERS
    // ══════════════════════════════════════════════════════════════════════

    @OptIn(FlowPreview::class)
    private fun observeViewModel() {

        // Survey state → update FAB utama & visibility kontrol
        combine(viewModel.isSurveying, viewModel.isPaused) { surveying, paused ->
            surveying to paused
        }.onEach { (surveying, paused) ->
            updateSurveyButton(surveying, paused)
            showSurveyFABs(surveying)
            if (!surveying) {
                chartController.clear()
                snackbarVoice?.dismiss()
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Mode SDI vs GENERAL → tampilkan FAB yang sesuai
        combine(viewModel.mode, viewModel.isSurveying) { mode, surveying ->
            mode to surveying
        }.onEach { (mode, surveying) ->
            val isSdi = mode == SurveyMode.SDI
            if (surveying) {
                fabCondition.visibility   = if (isSdi) View.GONE else View.VISIBLE
                fabSurface.visibility     = if (isSdi) View.GONE else View.VISIBLE
                fabAddDistress.visibility = if (isSdi) View.VISIBLE else View.GONE
                fabCamera.visibility      = if (isSdi) View.GONE else View.VISIBLE
                fabVoice.visibility       = if (isSdi) View.GONE else View.VISIBLE
            } else {
                fabCondition.visibility   = View.GONE
                fabSurface.visibility     = View.GONE
                fabAddDistress.visibility = View.GONE
                fabCamera.visibility      = View.GONE
                fabVoice.visibility       = View.GONE
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Lokasi → update marker peta & follow GPS
        viewModel.location.onEach { location ->
            location?.let {
                try {
                    mapRenderer.updateMyLocation(
                        it,
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_my_location)
                    )
                    if (isFollowingGPS) mapRenderer.smoothFollow(it)
                } catch (e: Exception) {
                    Timber.e(e, "Error updating location UI")
                }
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Polyline warna berdasarkan level getaran
        combine(
            viewModel.location,
            viewModel.vibration,
            viewModel.thresholdBaik,
            viewModel.thresholdSedang,
            viewModel.thresholdRusakRingan
        ) { location, vib, thB, thS, thR ->
            if (location != null && viewModel.isSurveying.value && !viewModel.isPaused.value) {
                val color = when {
                    vib < thB -> colorBaik
                    vib < thS -> colorSedang
                    vib < thR -> colorRusakRingan
                    else      -> colorRusakBerat
                }
                mapRenderer.addGeoPoint(GeoPoint(location.latitude, location.longitude), color)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Kecepatan
        viewModel.speed.onEach { speedMs ->
            binding.tvSpeed.text = getString(R.string.speed_format, (speedMs * 3.6f).toInt())
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Jarak
        viewModel.distance.onEach { dist ->
            binding.tvDistance.text = if (dist < 1000) {
                getString(R.string.distance_m_format, dist.toInt())
            } else {
                getString(R.string.distance_km_format, dist / 1000.0)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Kondisi & permukaan (mode GENERAL)
        viewModel.currentCondition.onEach { condition ->
            binding.tvCondition.text = condition.name
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.currentSurface.onEach { surface ->
            binding.tvSurface.text = surface.name
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Chart vibration history
        viewModel.vibrationHistory
            .sample(100L)
            .onEach { history ->
                try {
                    if (viewModel.isSurveying.value && !viewModel.isPaused.value) {
                        chartController.updateChart(
                            history              = history,
                            thresholdBaik        = viewModel.thresholdBaik.value,
                            thresholdSedang      = viewModel.thresholdSedang.value,
                            thresholdRusakRingan = viewModel.thresholdRusakRingan.value,
                            colorBaik            = colorBaik,
                            colorSedang          = colorSedang,
                            colorRusakRingan     = colorRusakRingan,
                            colorRusakBerat      = colorRusakBerat
                        ) { condition, color ->
                            binding.tvChartConditionBadge.text = condition
                            binding.tvChartConditionBadge.setTextColor(color)
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating chart")
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        // Z-axis vibration guide: panduan kondisi real-time untuk surveyor
        combine(
            viewModel.vibration,
            viewModel.thresholdBaik,
            viewModel.thresholdSedang,
            viewModel.thresholdRusakRingan,
            viewModel.isSurveying
        ) { vib, thB, thS, thR, surveying ->
            if (!surveying) return@combine null
            when {
                vib < thB -> Triple("RENDAH",       "Jalan tampak baik",           colorBaik)
                vib < thS -> Triple("SEDANG",        "Mungkin kondisi sedang",       colorSedang)
                vib < thR -> Triple("TINGGI",        "Kemungkinan rusak ringan",     colorRusakRingan)
                else      -> Triple("SANGAT TINGGI", "Kemungkinan rusak berat",      colorRusakBerat)
            }
        }.onEach { result ->
            if (_binding == null) return@onEach
            if (result == null) {
                binding.tvVibrationGuide.visibility = View.GONE
            } else {
                val (level, desc, color) = result
                binding.tvVibrationGuide.text = "Getaran: $level — $desc"
                binding.tvVibrationGuide.setTextColor(color)
                binding.tvVibrationGuide.visibility = View.VISIBLE
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Voice recording state → sync icon FAB + snackbar indikator
        mediaManager.isRecording.onEach { recording ->
            if (_binding == null) return@onEach
            fabVoice.setImageResource(
                if (recording) R.drawable.ic_stop else R.drawable.ic_mic
            )
            fabVoice.backgroundTintList = ContextCompat.getColorStateList(
                requireContext(),
                if (recording) R.color.red else R.color.primary
            )
            if (recording) {
                snackbarVoice = Snackbar.make(
                    binding.root,
                    "⏺ Merekam suara... ketuk lagi untuk berhenti",
                    Snackbar.LENGTH_INDEFINITE
                ).setAction("Stop") {
                    mediaManager.toggleVoiceRecording()
                }
                snackbarVoice?.show()
            } else {
                snackbarVoice?.dismiss()
                snackbarVoice = null
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)
    }

    // Auto-prompt foto tiap jarak tertentu (mode GENERAL saja)
    private fun observeDistanceTrigger() {
        viewModel.distanceTrigger.onEach { distance ->
            if (viewModel.isSurveying.value && !viewModel.isPaused.value
                && viewModel.mode.value == SurveyMode.GENERAL
            ) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.distance_reached_prompt, distance.toInt()),
                    Snackbar.LENGTH_LONG
                ).setAction(getString(R.string.take_photo)) {
                    mediaManager.openCamera()
                }.show()
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private fun showSurveyFABs(show: Boolean) {
        fabStop.visibility                = if (show) View.VISIBLE else View.GONE
        fabCamera.visibility              = if (show) View.VISIBLE else View.GONE
        fabVoice.visibility               = if (show) View.VISIBLE else View.GONE
        binding.chartContainer.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) binding.tvVibrationGuide.visibility = View.GONE
        // fabCondition, fabSurface, fabAddDistress diatur oleh observer mode
    }

    private fun updateSurveyButton(isSurveying: Boolean, isPaused: Boolean) {
        fabSurvey.setImageResource(
            when {
                !isSurveying || isPaused -> R.drawable.ic_play
                else                      -> R.drawable.ic_pause
            }
        )
        fabSurvey.backgroundTintList = ContextCompat.getColorStateList(
            requireContext(),
            when {
                !isSurveying -> R.color.primary
                isPaused     -> R.color.yellow
                else         -> R.color.green
            }
        )
    }

    private fun restoreSurveyState() {
        val isSurveying = viewModel.isSurveying.value
        val isPaused    = viewModel.isPaused.value
        updateSurveyButton(isSurveying, isPaused)
        if (isSurveying) {
            showSurveyFABs(true)
            isChartVisible = true
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}