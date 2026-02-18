package zaujaani.roadsensebasic.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
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
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.FlowPreview
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import timber.log.Timber
import zaujaani.roadsensebasic.BuildConfig
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.data.local.entity.RoadSegment
import zaujaani.roadsensebasic.databinding.FragmentMapBinding
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.util.Constants
import kotlin.math.abs
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

    // Peta
    private val pathPoints = mutableListOf<GeoPoint>()
    private var currentPolyline = Polyline()
    private val segmentMarkers = mutableListOf<Marker>()
    private var myLocationMarker: Marker? = null
    private var accuracyCircle: Polygon? = null

    // Untuk smooth follow camera
    private var lastCameraPoint: GeoPoint? = null

    // Smooth zoom
    private var lastZoomLevel: Double = 18.0

    // Smooth bearing
    private var lastBearing = 0f

    private var isChartVisible = true
    private var isFollowingGPS = true

    // Warna kondisi
    private val colorBaik by lazy { ContextCompat.getColor(requireContext(), R.color.green) }
    private val colorSedang by lazy { ContextCompat.getColor(requireContext(), R.color.yellow) }
    private val colorRusakRingan by lazy { ContextCompat.getColor(requireContext(), R.color.orange) }
    private val colorRusakBerat by lazy { ContextCompat.getColor(requireContext(), R.color.red) }
    private val colorDefault by lazy { ContextCompat.getColor(requireContext(), R.color.primary) }

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
        setupMap()
        setupChart()
        setupClickListeners()
        observeViewModel()
        restoreSurveyState()

        // Listener untuk notifikasi segmen tersimpan - dinonaktifkan sementara karena memerlukan fungsi di ViewModel
        // Jika ingin diaktifkan, tambahkan fungsi getSegmentsForSession di ViewModel
        /*
        parentFragmentManager.setFragmentResultListener("segment_saved", viewLifecycleOwner) { _, bundle ->
            val sessionId = bundle.getLong("session_id", -1)
            if (sessionId > 0) {
                // Implementasi jika ViewModel memiliki fungsi getSegmentsForSession
                // viewModel.getSegmentsForSession(sessionId).observe(viewLifecycleOwner) { segments: List<RoadSegment>? ->
                //     segments?.forEach { segment -> addSegmentMarker(segment) }
                // }
            }
        }
        */
    }

    private fun bindViews() {
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
    }

    // ========== PETA ==========

    private fun setupMap() {
        mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(18.0)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setTilesScaledToDpi(true)
        }
        initCurrentPolyline()
    }

    private fun initCurrentPolyline() {
        currentPolyline = Polyline().apply {
            outlinePaint.color = colorDefault
            outlinePaint.strokeWidth = 8f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        mapView.overlays.add(currentPolyline)
    }

    private fun addGeoPoint(geoPoint: GeoPoint, conditionColor: Int) {
        if (pathPoints.isNotEmpty() && pathPoints.last().distanceToAsDouble(geoPoint) < 2.0) return
        pathPoints.add(geoPoint)
        currentPolyline.outlinePaint.color = conditionColor
        currentPolyline.setPoints(pathPoints.toList())
        mapView.invalidate()
    }

    /**
     * Menambahkan marker untuk segmen (start dan end). Dipanggil saat segmen selesai dibuat.
     */
    private fun addSegmentMarker(segment: RoadSegment) {
        // Validasi koordinat start
        if (segment.startLat == 0.0 && segment.startLng == 0.0) {
            Timber.w("addSegmentMarker: startLat/Lng 0, skip")
            return
        }

        // Marker START
        val startMarker = Marker(mapView).apply {
            position = GeoPoint(segment.startLat, segment.startLng)
            title = getString(R.string.segment_start_title, segment.name)
            snippet = getString(
                R.string.segment_marker_snippet,
                segment.conditionAuto,
                segment.surfaceType,
                (segment.endDistance - segment.startDistance).toInt()
            )
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_start)
            isFlat = true
        }

        // Marker END (fallback ke start jika koordinat end tidak valid)
        val endMarker = Marker(mapView).apply {
            position = GeoPoint(
                segment.endLat.takeIf { it != 0.0 } ?: segment.startLat,
                segment.endLng.takeIf { it != 0.0 } ?: segment.startLng
            )
            title = getString(R.string.segment_end_title, segment.name)
            snippet = getString(R.string.segment_condition_label, segment.conditionAuto)
            icon = getConditionMarkerIcon(segment.conditionAuto)
            isFlat = true
        }

        // Simpan referensi marker
        segmentMarkers.add(startMarker)
        segmentMarkers.add(endMarker)

        // Tambahkan ke overlay
        mapView.overlays.add(startMarker)
        mapView.overlays.add(endMarker)

        mapView.invalidate()
        Timber.d("addSegmentMarker: added start and end markers for segment ${segment.name}")
    }

    // ===== MARKER LOKASI + LINGKARAN AKURASI (PRO) =====
    private fun updateMyLocationUI(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)

        // 1. Marker lokasi saya
        if (myLocationMarker == null) {
            myLocationMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_my_location)
                isFlat = true
                mapView.overlays.add(this)
            }
        }
        myLocationMarker?.apply {
            position = geoPoint
            rotation = location.bearing
        }

        // 2. Hapus lingkaran lama jika ada
        accuracyCircle?.let { mapView.overlays.remove(it) }

        // 3. Buat lingkaran baru dengan modifikasi Paint yang sudah ada (bukan reassign)
        accuracyCircle = Polygon().apply {
            points = Polygon.pointsAsCircle(geoPoint, location.accuracy.toDouble())

            // Fill paint - gunakan properti yang sudah ada, jangan buat Paint baru
            fillPaint.apply {
                style = android.graphics.Paint.Style.FILL
                color = 0x332196F3.toInt()  // biru transparan 20%
                isAntiAlias = true
            }

            // Outline paint - modifikasi properti yang sudah ada
            outlinePaint.apply {
                style = android.graphics.Paint.Style.STROKE
                color = 0x882196F3.toInt()  // biru lebih gelap 53%
                strokeWidth = 2f
                isAntiAlias = true
            }
        }

        // 4. Tambahkan lingkaran ke map
        accuracyCircle?.let { mapView.overlays.add(it) }

        // 5. Pastikan marker lokasi berada di atas lingkaran
        myLocationMarker?.let { marker ->
            mapView.overlays.remove(marker)
            mapView.overlays.add(marker)
        }

        // 6. Zoom dan navigasi
        adjustZoomBasedOnAccuracy(location.accuracy)
        updateNavigationMode(location)

        mapView.invalidate()
    }

    /**
     * Smooth follow camera dengan interpolasi adaptif berdasarkan kecepatan
     */
    private fun smoothFollow(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)

        if (lastCameraPoint == null) {
            mapView.controller.setCenter(geoPoint)
            lastCameraPoint = geoPoint
            return
        }

        // Faktor interpolasi berdasarkan kecepatan (m/s)
        val factor = when {
            location.speed < 3f -> 0.1   // jalan kaki / pelan
            location.speed < 10f -> 0.2  // motor / sedang
            else -> 0.3                   // mobil / cepat
        }

        val lat = lastCameraPoint!!.latitude +
                (geoPoint.latitude - lastCameraPoint!!.latitude) * factor
        val lon = lastCameraPoint!!.longitude +
                (geoPoint.longitude - lastCameraPoint!!.longitude) * factor

        val smoothPoint = GeoPoint(lat, lon)
        mapView.controller.setCenter(smoothPoint)
        lastCameraPoint = smoothPoint
    }

    /**
     * Menyesuaikan zoom berdasarkan akurasi GPS, dengan ambang batas untuk menghindari lompatan
     */
    private fun adjustZoomBasedOnAccuracy(accuracy: Float) {
        val targetZoom = when {
            accuracy < 5 -> 19.5
            accuracy < 10 -> 18.5
            accuracy < 20 -> 17.5
            accuracy < 50 -> 16.5
            else -> 15.5
        }

        // Hanya ubah zoom jika perbedaan cukup signifikan (> 0.5)
        if (abs(targetZoom - lastZoomLevel) > 0.5) {
            mapView.controller.setZoom(targetZoom)
            lastZoomLevel = targetZoom
        }
    }

    /**
     * Memutar peta mengikuti bearing dengan smoothing untuk menghindari jitter
     */
    private fun updateNavigationMode(location: Location) {
        val rawBearing = location.bearing
        // Smoothing dengan faktor 0.2 (20% dari perubahan)
        val smoothBearing = lastBearing + (rawBearing - lastBearing) * 0.2f
        lastBearing = smoothBearing

        mapView.setMapOrientation(-smoothBearing)   // OSMDroid: orientasi positif = berlawanan arah jarum jam
    }

    private fun getConditionMarkerIcon(condition: String) = ContextCompat.getDrawable(
        requireContext(),
        when (condition) {
            Constants.CONDITION_BAIK -> R.drawable.ic_marker_green
            Constants.CONDITION_SEDANG -> R.drawable.ic_marker_yellow
            Constants.CONDITION_RUSAK_RINGAN -> R.drawable.ic_marker_orange
            else -> R.drawable.ic_marker_red
        }
    )

    // ===== CLEAR (TIDAK HAPUS MARKER LOKASI) =====
    private fun clearMapOverlays() {
        mapView.overlays.removeAll(segmentMarkers)
        mapView.overlays.remove(currentPolyline)

        pathPoints.clear()
        segmentMarkers.clear()

        initCurrentPolyline()
        mapView.invalidate()
    }

    // ========== CHART ==========

    private fun setupChart() {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(false)
            isDragEnabled = false
            setScaleEnabled(false)
            legend.isEnabled = false
            xAxis.isEnabled = false
            axisLeft.apply {
                setDrawLabels(true)
                textColor = Color.WHITE
                textSize = 9f
                removeAllLimitLines()
                addLimitLine(LimitLine(Constants.DEFAULT_THRESHOLD_BAIK, getString(R.string.condition_baik)).apply {
                    lineColor = colorBaik; textColor = colorBaik; textSize = 8f
                })
                addLimitLine(LimitLine(Constants.DEFAULT_THRESHOLD_SEDANG, getString(R.string.condition_sedang)).apply {
                    lineColor = colorSedang; textColor = colorSedang; textSize = 8f
                })
                addLimitLine(LimitLine(Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN, getString(R.string.condition_rusak_ringan)).apply {
                    lineColor = colorRusakBerat; textColor = colorRusakBerat; textSize = 8f
                })
            }
            axisRight.isEnabled = false
            setBackgroundColor(Color.argb(128, 255, 255, 255)) // semi-transparan putih
            setNoDataText(getString(R.string.chart_no_data))
            setNoDataTextColor(Color.WHITE)
        }
    }

    private fun updateChart(history: List<Float>) {
        Timber.d("updateChart called, size=${history.size}, last=${history.lastOrNull()}")
        if (!isChartVisible || history.isEmpty()) return

        val entries = history.mapIndexed { index, value ->
            Entry(index.toFloat(), value)
        }
        val dataSet = LineDataSet(entries, getString(R.string.chart_z_label)).apply {
            color = colorDefault
            setDrawCircles(false)
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillAlpha = 40
            fillColor = colorDefault
            val lastVal = history.lastOrNull() ?: 0f
            color = getConditionColor(lastVal)
        }
        chart.data = LineData(dataSet)
        chart.notifyDataSetChanged()
        chart.invalidate()
        chart.moveViewToX(entries.size.toFloat())
    }

    private fun getConditionColor(vibValue: Float): Int {
        val thB = viewModel.thresholdBaik.value
        val thS = viewModel.thresholdSedang.value
        val thR = viewModel.thresholdRusakRingan.value
        return when {
            vibValue < thB -> colorBaik
            vibValue < thS -> colorSedang
            vibValue < thR -> colorRusakRingan
            else -> colorRusakBerat
        }
    }

    // ========== CLICK LISTENERS ==========

    private fun setupClickListeners() {
        fabSurvey.setOnClickListener { handleSurveyButtonClick() }
        fabStop.setOnClickListener { showStopSurveyConfirmation() }
        fabSegment.setOnClickListener { handleSegmentButtonClick() }

        fabToggleChart.setOnClickListener {
            isChartVisible = !isChartVisible
            binding.chartContainer.visibility = if (isChartVisible) View.VISIBLE else View.GONE
            fabToggleChart.setImageResource(
                if (isChartVisible) R.drawable.ic_chart else R.drawable.ic_chart_off
            )
        }

        fabCamera.setOnClickListener {
            if (viewModel.isSurveying.value && !viewModel.isPaused.value) {
                openQuickCamera()
            } else {
                showToast(getString(R.string.survey_not_active))
            }
        }

        fabVoice.setOnClickListener {
            if (viewModel.isSurveying.value && !viewModel.isPaused.value) {
                openQuickVoiceNote()
            } else {
                showToast(getString(R.string.survey_not_active))
            }
        }

        fabCondition.setOnClickListener { showConditionPicker() }
        fabSurface.setOnClickListener { showSurfacePicker() }
    }

    private fun handleSurveyButtonClick() {
        when {
            !viewModel.isSurveying.value -> showStartSurveyDialog()
            viewModel.isPaused.value -> viewModel.resumeSurvey()
            else -> viewModel.pauseSurvey()
        }
    }

    private fun handleSegmentButtonClick() {
        if (viewModel.isSegmentStarted.value) {
            viewModel.endSegment(findNavController())
        } else {
            viewModel.startSegment()
            showToast(getString(R.string.segment_started_toast))
        }
    }

    private fun showStartSurveyDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_start_survey, null)
        val etSurveyor = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSurveyorName)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.start_survey))
            .setView(view)
            .setPositiveButton(getString(R.string.start)) { _, _ ->
                val surveyor = etSurveyor?.text?.toString()?.trim() ?: ""

                if (surveyor.isBlank()) {
                    showToast("Nama surveyor wajib diisi")
                    return@setPositiveButton
                }

                startSurvey(surveyor)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun startSurvey(surveyorName: String) {
        viewModel.startSurvey(surveyorName)
        clearMapOverlays()
        showSurveyFABs(true)
        // Tampilkan chart secara otomatis
        binding.chartContainer.visibility = View.VISIBLE
        isChartVisible = true
        fabToggleChart.setImageResource(R.drawable.ic_chart)
    }

    private fun openQuickCamera() {
        showToast(getString(R.string.camera_quick_tip))
        findNavController().navigate(
            MapFragmentDirections.actionMapFragmentToSegmentBottomSheet(
                sessionId = 0L,
                startDistance = 0f,
                endDistance = 0f,
                avgVibration = 0f,
                conditionAuto = viewModel.currentAutoCondition.value,
                confidence = viewModel.currentConfidence.value,
                tempCondition = viewModel.tempCondition.value,
                tempSurface = viewModel.tempSurface.value
            )
        )
    }

    private fun openQuickVoiceNote() {
        showToast(getString(R.string.voice_quick_tip))
        // TODO: implement voice note
    }

    private fun showConditionPicker() {
        val conditions = listOf(
            Constants.CONDITION_BAIK,
            Constants.CONDITION_SEDANG,
            Constants.CONDITION_RUSAK_RINGAN,
            Constants.CONDITION_RUSAK_BERAT
        )
        val currentIndex = conditions.indexOf(viewModel.tempCondition.value).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.pick_condition_title))
            .setSingleChoiceItems(conditions.toTypedArray(), currentIndex) { dialog, which ->
                viewModel.setTemporaryCondition(conditions[which])
                dialog.dismiss()
                showToast(getString(R.string.condition_selected, conditions[which]))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSurfacePicker() {
        val surfaces = Constants.SURFACE_TYPES
        val currentIndex = surfaces.indexOf(viewModel.tempSurface.value).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.pick_surface_title))
            .setSingleChoiceItems(surfaces.toTypedArray(), currentIndex) { dialog, which ->
                viewModel.setTemporarySurface(surfaces[which])
                dialog.dismiss()
                showToast(getString(R.string.surface_selected, surfaces[which]))
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
                findNavController().navigate(R.id.summaryFragment)
            }
            .setNegativeButton(getString(R.string.discard)) { _, _ ->
                viewModel.stopSurveyAndDiscard()
                showSurveyFABs(false)
                clearMapOverlays()
            }
            .setNeutralButton(getString(R.string.resume)) { _, _ ->
                viewModel.resumeSurvey()
            }
            .show()
    }

    private fun showSurveyFABs(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        fabStop.visibility = visibility
        fabSegment.visibility = visibility
        fabCamera.visibility = visibility
        fabVoice.visibility = visibility
        fabCondition.visibility = visibility
        fabSurface.visibility = visibility
    }

    private fun updateSurveyButton(isSurveying: Boolean, isPaused: Boolean) {
        fabSurvey.setImageResource(when {
            !isSurveying -> R.drawable.ic_play
            isPaused -> R.drawable.ic_play
            else -> R.drawable.ic_pause
        })
        fabSurvey.backgroundTintList = ContextCompat.getColorStateList(
            requireContext(),
            when {
                !isSurveying -> R.color.primary
                isPaused -> R.color.yellow
                else -> R.color.green
            }
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    // ========== OBSERVE VIEW MODEL ==========

    @OptIn(FlowPreview::class)
    private fun observeViewModel() {
        combine(viewModel.isSurveying, viewModel.isPaused) { surveying, paused ->
            surveying to paused
        }.onEach { (surveying, paused) ->
            updateSurveyButton(surveying, paused)
            if (surveying) showSurveyFABs(true)
            else showSurveyFABs(false)
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.isSegmentStarted.onEach { started ->
            fabSegment.setImageResource(
                if (started) R.drawable.ic_flag_end else R.drawable.ic_flag_start
            )
            fabSegment.backgroundTintList = ContextCompat.getColorStateList(
                requireContext(),
                if (started) R.color.orange else R.color.accent
            )
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Lokasi → update peta dan marker
        viewModel.location.onEach { location ->
            location?.let {
                try {
                    updateMyLocationUI(it)  // marker + lingkaran + zoom + rotasi
                    if (isFollowingGPS) {
                        smoothFollow(it)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating location UI")
                }
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Gambar polyline saat survey aktif
        combine(
            viewModel.location,
            viewModel.vibration,
            viewModel.thresholdBaik,
            viewModel.thresholdSedang,
            viewModel.thresholdRusakRingan
        ) { location, vib, thB, thS, thR ->
            if (location != null && viewModel.isSurveying.value && !viewModel.isPaused.value) {
                val conditionColor = when {
                    vib < thB -> colorBaik
                    vib < thS -> colorSedang
                    vib < thR -> colorRusakRingan
                    else -> colorRusakBerat
                }
                addGeoPoint(GeoPoint(location.latitude, location.longitude), conditionColor)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.speed.onEach { speedMs ->
            binding.tvSpeed.text = getString(R.string.speed_format, (speedMs * 3.6f).toInt())
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.distance.onEach { dist ->
            binding.tvDistance.text = if (dist < 1000) {
                getString(R.string.distance_m_format, dist.toInt())
            } else {
                getString(R.string.distance_km_format, dist / 1000.0)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.vibration.onEach { vib ->
            binding.tvVibration.text = getString(R.string.vibration_format, vib)
            binding.tvVibration.setTextColor(getConditionColor(vib))
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.gpsAccuracy.onEach { acc ->
            binding.tvGpsAccuracy.text = getString(R.string.accuracy_format, acc)
            binding.tvGpsAccuracy.setTextColor(when {
                acc < 5f -> colorBaik
                acc < 15f -> colorSedang
                else -> colorRusakBerat
            })
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.currentAutoCondition.onEach { condition ->
            binding.tvCondition.text = condition
            binding.tvCondition.setTextColor(getConditionColor(
                when (condition) {
                    Constants.CONDITION_BAIK -> 0f
                    Constants.CONDITION_SEDANG -> viewModel.thresholdBaik.value + 0.1f
                    Constants.CONDITION_RUSAK_RINGAN -> viewModel.thresholdSedang.value + 0.1f
                    else -> viewModel.thresholdRusakRingan.value + 0.1f
                }
            ))
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.tempSurface.onEach { surface ->
            binding.tvSurface.text = surface
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Chart update dengan sampling untuk performa
        viewModel.vibrationHistory
            .sample(100L) // throttle 10 Hz
            .onEach { history ->
                try {
                    if (viewModel.isSurveying.value && !viewModel.isPaused.value) {
                        updateChart(history)
                    }
                    if (!viewModel.isSurveying.value) {
                        chart.clear()
                        chart.setNoDataText(getString(R.string.chart_no_data))
                        chart.invalidate()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating chart")
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun restoreSurveyState() {
        val isSurveying = viewModel.isSurveying.value
        val isPaused = viewModel.isPaused.value
        updateSurveyButton(isSurveying, isPaused)
        if (isSurveying) {
            showSurveyFABs(true)
            binding.chartContainer.visibility = View.VISIBLE
            isChartVisible = true
        }
    }

    // ========== LIFECYCLE ==========

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        sensorGateway.startListening()
        if (hasLocationPermission()) {
            viewModel.startLocationTracking()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        if (!viewModel.isSurveying.value) {
            sensorGateway.stopListening()
            viewModel.stopLocationTracking()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView.overlays.clear()
        if (!viewModel.isSurveying.value) {
            sensorGateway.stopListening()
            viewModel.stopLocationTracking()
        }
        _binding = null
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}