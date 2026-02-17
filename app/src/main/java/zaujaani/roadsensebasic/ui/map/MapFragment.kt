package zaujaani.roadsensebasic.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import timber.log.Timber
import zaujaani.roadsensebasic.BuildConfig
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.databinding.FragmentMapBinding
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.util.Constants
import javax.inject.Inject

@AndroidEntryPoint
class MapFragment : Fragment() {

    @Inject
    lateinit var sensorGateway: SensorGateway

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels()

    private lateinit var mapView: MapView
    private lateinit var chart: LineChart
    private lateinit var fabSurvey: FloatingActionButton
    private lateinit var fabSegment: FloatingActionButton
    private lateinit var fabStop: FloatingActionButton
    private lateinit var fabToggleChart: FloatingActionButton
    private lateinit var fabCamera: FloatingActionButton
    private lateinit var fabVoice: FloatingActionButton
    private lateinit var fabCondition: FloatingActionButton
    private lateinit var fabSurface: FloatingActionButton

    private var pathOverlay = Polyline()
    private val points = mutableListOf<GeoPoint>()
    private var isChartVisible = true

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

        Configuration.getInstance().load(
            requireContext(),
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        )
        Configuration.getInstance().setUserAgentValue("${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")

        mapView = binding.mapView
        chart = binding.chartVibration
        fabSurvey = binding.fabSurvey
        fabSegment = binding.fabSegment
        fabStop = binding.fabStop
        fabToggleChart = binding.fabToggleChart
        fabCamera = binding.fabCamera
        fabVoice = binding.fabVoice
        fabCondition = binding.fabCondition
        fabSurface = binding.fabSurface

        setupMap()
        setupChart()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)
        mapView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        mapView.setTilesScaledToDpi(true)
    }

    private fun setupChart() {
        chart.description.isEnabled = false
        chart.setTouchEnabled(false)
        chart.isDragEnabled = false
        chart.setScaleEnabled(false)
        chart.legend.isEnabled = false
        chart.xAxis.isEnabled = false
        chart.axisLeft.setDrawLabels(false)
        chart.axisRight.isEnabled = false
    }

    private fun setupClickListeners() {
        fabSurvey.setOnClickListener {
            when {
                !viewModel.isSurveying.value -> {
                    // Start survey
                    viewModel.startSurvey()
                    fabSurvey.setImageResource(R.drawable.ic_pause)
                    fabStop.visibility = View.VISIBLE
                    fabSegment.visibility = View.VISIBLE
                    fabCamera.visibility = View.VISIBLE
                    fabVoice.visibility = View.VISIBLE
                    fabCondition.visibility = View.VISIBLE
                    fabSurface.visibility = View.VISIBLE
                    fabSegment.setImageResource(R.drawable.ic_flag_start)
                    points.clear()
                    mapView.overlays.remove(pathOverlay)
                    pathOverlay = Polyline().apply {
                        outlinePaint.color = requireContext().getColor(R.color.primary)
                        outlinePaint.strokeWidth = 5f
                    }
                    mapView.overlays.add(pathOverlay)
                    mapView.invalidate()
                }
                viewModel.isPaused.value -> {
                    viewModel.resumeSurvey()
                    fabSurvey.setImageResource(R.drawable.ic_pause)
                }
                else -> {
                    viewModel.pauseSurvey()
                    fabSurvey.setImageResource(R.drawable.ic_play)
                }
            }
        }

        fabStop.setOnClickListener {
            showStopSurveyConfirmation()
        }

        fabSegment.setOnClickListener {
            if (viewModel.isSegmentStarted.value) {
                viewModel.endSegment(findNavController())
                fabSegment.setImageResource(R.drawable.ic_flag_start)
            } else {
                viewModel.startSegment()
                fabSegment.setImageResource(R.drawable.ic_flag_end)
            }
        }

        fabToggleChart.setOnClickListener {
            isChartVisible = !isChartVisible
            binding.chartContainer.visibility = if (isChartVisible) View.VISIBLE else View.GONE
            fabToggleChart.setImageResource(
                if (isChartVisible) R.drawable.ic_chart else R.drawable.ic_chart_off
            )
        }

        fabCamera.setOnClickListener {
            Toast.makeText(requireContext(), "Fitur kamera segera hadir", Toast.LENGTH_SHORT).show()
        }

        fabVoice.setOnClickListener {
            Toast.makeText(requireContext(), "Fitur voice segera hadir", Toast.LENGTH_SHORT).show()
        }

        fabCondition.setOnClickListener {
            showConditionPicker()
        }

        fabSurface.setOnClickListener {
            showSurfacePicker()
        }
    }

    private fun showConditionPicker() {
        val conditions = listOf(
            Constants.CONDITION_BAIK,
            Constants.CONDITION_SEDANG,
            Constants.CONDITION_RUSAK_RINGAN,
            Constants.CONDITION_RUSAK_BERAT
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pilih Kondisi Jalan")
            .setSingleChoiceItems(conditions.toTypedArray(), conditions.indexOf(viewModel.tempCondition.value)) { dialog, which ->
                val selected = conditions[which]
                viewModel.setTemporaryCondition(selected)
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSurfacePicker() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pilih Jenis Permukaan")
            .setSingleChoiceItems(Constants.SURFACE_TYPES.toTypedArray(), Constants.SURFACE_TYPES.indexOf(viewModel.tempSurface.value)) { dialog, which ->
                val selected = Constants.SURFACE_TYPES[which]
                viewModel.setTemporarySurface(selected)
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showStopSurveyConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Hentikan Survey")
            .setMessage("Apakah Anda yakin ingin menghentikan survey?")
            .setPositiveButton("Ya") { _, _ ->
                viewModel.pauseSurvey()
                showSaveConfirmation()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSaveConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Simpan Data?")
            .setMessage("Apakah Anda ingin menyimpan data survey ini?")
            .setPositiveButton("Simpan") { _, _ ->
                viewModel.stopSurveyAndSave()
                resetUiAfterStop()
                findNavController().navigate(R.id.summaryFragment)
            }
            .setNegativeButton("Buang") { _, _ ->
                viewModel.stopSurveyAndDiscard()
                resetUiAfterStop()
            }
            .show()
    }

    private fun resetUiAfterStop() {
        fabSurvey.setImageResource(R.drawable.ic_play)
        fabStop.visibility = View.GONE
        fabSegment.visibility = View.GONE
        fabCamera.visibility = View.GONE
        fabVoice.visibility = View.GONE
        fabCondition.visibility = View.GONE
        fabSurface.visibility = View.GONE
        points.clear()
        mapView.overlays.remove(pathOverlay)
        mapView.invalidate()
    }

    private fun observeViewModel() {
        combine(
            viewModel.location,
            viewModel.thresholdBaik,
            viewModel.thresholdSedang,
            viewModel.thresholdRusakRingan
        ) { location, baik, sedang, rusakRingan ->
            location to Triple(baik, sedang, rusakRingan)
        }.onEach { (location, thresholds) ->
            location?.let {
                val geoPoint = GeoPoint(it.latitude, it.longitude)
                if (points.isEmpty()) {
                    mapView.controller.setCenter(geoPoint)
                }

                if (viewModel.isSurveying.value && !viewModel.isPaused.value) {
                    if (points.isEmpty() || points.last().distanceToAsDouble(geoPoint) > 3.0) {
                        points.add(geoPoint)
                        pathOverlay.setPoints(points)
                    }

                    val lastVib = viewModel.vibration.value
                    val colorRes = when {
                        lastVib < thresholds.first -> R.color.green
                        lastVib < thresholds.second -> R.color.yellow
                        lastVib < thresholds.third -> R.color.orange
                        else -> R.color.red
                    }
                    pathOverlay.outlinePaint.color = requireContext().getColor(colorRes)

                    mapView.invalidate()
                }
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.speed.onEach { speedMs ->
            binding.tvSpeed.text = getString(R.string.speed_format, (speedMs * 3.6).toInt())
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.distance.onEach { dist ->
            binding.tvDistance.text = if (dist < 1000) {
                getString(R.string.distance_m_format, dist.toInt())
            } else {
                getString(R.string.distance_km_format, dist / 1000)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.vibration.onEach { vib ->
            binding.tvVibration.text = getString(R.string.vibration_format, vib)
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.gpsAccuracy.onEach { acc ->
            binding.tvGpsAccuracy.text = getString(R.string.accuracy_format, acc)
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.vibrationHistory.onEach { history ->
            if (isChartVisible) {
                updateChart(history)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.isSegmentStarted.onEach { started ->
            if (started) {
                fabSegment.setImageResource(R.drawable.ic_flag_end)
            } else {
                fabSegment.setImageResource(R.drawable.ic_flag_start)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Observasi kondisi & permukaan untuk update UI
        viewModel.tempCondition.onEach { condition ->
            binding.tvCondition.text = condition
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.tempSurface.onEach { surface ->
            binding.tvSurface.text = surface
        }.launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun updateChart(history: List<Float>) {
        val entries = history.mapIndexed { index, value ->
            Entry(index.toFloat(), value)
        }
        val dataSet = LineDataSet(entries, null).apply {
            color = requireContext().getColor(R.color.accent)
            setDrawCircles(false)
            lineWidth = 2f
        }
        chart.data = LineData(dataSet)
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        sensorGateway.startListening()
        mapView.onResume()
        if (hasLocationPermission()) {
            viewModel.startLocationTracking()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorGateway.stopListening()
        mapView.onPause()
        viewModel.stopLocationTracking()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView.overlays.clear()
        _binding = null
    }
}