package zaujaani.roadsensebasic.ui.map

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
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
import zaujaani.roadsensebasic.data.local.entity.Condition
import zaujaani.roadsensebasic.data.local.entity.Surface
import zaujaani.roadsensebasic.databinding.FragmentMapBinding
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.util.Constants
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import javax.inject.Inject

@AndroidEntryPoint
class MapFragment : Fragment() {

    @Inject
    lateinit var sensorGateway: SensorGateway

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels()

    // ── Views ──────────────────────────────────────────────────────────────────
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

    // ── Map overlays ───────────────────────────────────────────────────────────
    private val pathPoints = mutableListOf<GeoPoint>()
    private var currentPolyline = Polyline()
    private var myLocationMarker: Marker? = null
    private var accuracyCircle: Polygon? = null

    // ── Map state ──────────────────────────────────────────────────────────────
    private var lastCameraPoint: GeoPoint? = null
    private var lastZoomLevel: Double = 18.0
    private var lastBearing = 0f
    private var isChartVisible = true
    private var isFollowingGPS = true
    private var isOrientationEnabled = true

    // ── Colors (lazy) ──────────────────────────────────────────────────────────
    private val colorBaik by lazy { ContextCompat.getColor(requireContext(), R.color.green) }
    private val colorSedang by lazy { ContextCompat.getColor(requireContext(), R.color.yellow) }
    private val colorRusakRingan by lazy { ContextCompat.getColor(requireContext(), R.color.orange) }
    private val colorRusakBerat by lazy { ContextCompat.getColor(requireContext(), R.color.red) }
    private val colorDefault by lazy { ContextCompat.getColor(requireContext(), R.color.primary) }

    // ── Camera ─────────────────────────────────────────────────────────────────
    private var currentPhotoUri: android.net.Uri? = null

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && currentPhotoPath != null) {
                Timber.d("Photo saved: $currentPhotoPath")
                showToast(getString(R.string.photo_saved))
                viewModel.recordPhoto(currentPhotoPath!!)
                currentPhotoPath = null
            } else {
                showToast(getString(R.string.photo_failed))
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera()
            else showToast(getString(R.string.camera_permission_required))
        }

    // ── Voice Recording ────────────────────────────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var isRecording = false
    private var recordingTimerJob: Job? = null
    private var recordingSeconds = 0

    private var currentPhotoPath: String? = null


    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceRecording()
            else showToast(getString(R.string.microphone_permission_required))
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

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
        if (!viewModel.isSurveying.value) {
            sensorGateway.stopListening()
            viewModel.stopLocationTracking()
        }
        // Hentikan recording jika fragment di-pause
        if (isRecording) stopVoiceRecording()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView.overlays.clear()
        recordingTimerJob?.cancel()
        releaseMediaRecorder()
        if (!viewModel.isSurveying.value) {
            sensorGateway.stopListening()
            viewModel.stopLocationTracking()
        }
        _binding = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        mapView = binding.mapView
        chart = binding.chartVibration
        fabSurvey = binding.fabSurvey
        fabStop = binding.fabStop
        fabToggleChart = binding.fabToggleChart
        fabCamera = binding.fabCamera
        fabVoice = binding.fabVoice
        fabCondition = binding.fabCondition
        fabSurface = binding.fabSurface
        fabToggleOrientation = binding.fabToggleOrientation
    }

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
                // Warna putih agar kontras di atas background gelap
                textColor = Color.WHITE
                textSize = 10f
                gridColor = Color.argb(80, 255, 255, 255)
                axisLineColor = Color.WHITE
                removeAllLimitLines()
                addLimitLine(
                    LimitLine(Constants.DEFAULT_THRESHOLD_BAIK, getString(R.string.condition_baik)).apply {
                        lineColor = colorBaik
                        textColor = colorBaik
                        textSize = 9f
                        lineWidth = 1.5f
                    }
                )
                addLimitLine(
                    LimitLine(Constants.DEFAULT_THRESHOLD_SEDANG, getString(R.string.condition_sedang)).apply {
                        lineColor = colorSedang
                        textColor = colorSedang
                        textSize = 9f
                        lineWidth = 1.5f
                    }
                )
                addLimitLine(
                    LimitLine(Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN, getString(R.string.condition_rusak_ringan)).apply {
                        lineColor = colorRusakBerat
                        textColor = colorRusakBerat
                        textSize = 9f
                        lineWidth = 1.5f
                    }
                )
            }
            axisRight.isEnabled = false

            // PENTING: background gelap agar semua elemen putih kelihatan
            setBackgroundColor(Color.argb(200, 15, 15, 30))

            setNoDataText(getString(R.string.chart_no_data))
            setNoDataTextColor(Color.WHITE)

            // Border chart
            setDrawBorders(true)
            setBorderColor(Color.argb(100, 255, 255, 255))
            setBorderWidth(0.5f)
        }
    }

    private fun setupClickListeners() {
        fabSurvey.setOnClickListener { handleSurveyButtonClick() }
        fabStop.setOnClickListener { showStopSurveyConfirmation() }

        fabToggleChart.setOnClickListener {
            isChartVisible = !isChartVisible
            binding.chartContainer.visibility = if (isChartVisible) View.VISIBLE else View.GONE
            fabToggleChart.setImageResource(
                if (isChartVisible) R.drawable.ic_chart else R.drawable.ic_chart_off
            )
        }

        fabCamera.setOnClickListener {
            if (viewModel.isSurveying.value && !viewModel.isPaused.value) {
                openCamera()
            } else {
                showToast(getString(R.string.survey_not_active))
            }
        }

        fabVoice.setOnClickListener {
            if (viewModel.isSurveying.value && !viewModel.isPaused.value) {
                if (isRecording) stopVoiceRecording()
                else openVoiceRecorder()
            } else {
                showToast(getString(R.string.survey_not_active))
            }
        }

        binding.btnStopMapRecording.setOnClickListener {
            stopVoiceRecording()
        }

        fabCondition.setOnClickListener { showConditionPicker() }
        fabSurface.setOnClickListener { showSurfacePicker() }

        fabToggleOrientation.setOnClickListener {
            isOrientationEnabled = !isOrientationEnabled
            if (isOrientationEnabled) {
                showToast(getString(R.string.orientation_enabled))
            } else {
                showToast(getString(R.string.orientation_disabled))
                mapView.setMapOrientation(0f)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera
    // ─────────────────────────────────────────────────────────────────────────

    private fun openCamera() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> launchCamera()

            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            val photoFile = createImageFile()
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                photoFile
            )
            currentPhotoUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: IOException) {
            Timber.e(e, "Error creating photo file")
            showToast(getString(R.string.photo_failed))
        }
    }

    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("ROADSENSE_${timestamp}_", ".jpg", storageDir).apply {
            currentPhotoPath = absolutePath
        }
    }
    // ─────────────────────────────────────────────────────────────────────────
    // Voice Recording
    // ─────────────────────────────────────────────────────────────────────────

    private fun openVoiceRecorder() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> startVoiceRecording()

            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecording() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val audioDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val audioFile = File(audioDir, "ROADSENSE_AUDIO_${timestamp}.mp4")
            currentAudioFile = audioFile

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(requireContext())
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            recordingSeconds = 0
            showRecordingIndicator(true)
            startRecordingTimer()

            // Ganti ikon FAB voice jadi stop (merah)
            fabVoice.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.red)

            Timber.d("Recording started: ${audioFile.absolutePath}")

        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording")
            showToast(getString(R.string.recording_failed))
            releaseMediaRecorder()
        }
    }

    private fun stopVoiceRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
            }
            currentAudioFile?.let { file ->
                viewModel.recordVoice(file.absolutePath)
                Timber.d("Recording saved: ${file.absolutePath}")
                showToast(getString(R.string.play_recording))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping recording")
            showToast(getString(R.string.recording_failed))
        } finally {
            releaseMediaRecorder()
            isRecording = false
            recordingTimerJob?.cancel()
            showRecordingIndicator(false)
            // Reset warna FAB voice
            fabVoice.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.primary_dark)
        }
    }

    private fun releaseMediaRecorder() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Timber.e(e, "Error releasing MediaRecorder")
        }
        mediaRecorder = null
    }

    private fun showRecordingIndicator(show: Boolean) {
        binding.voiceRecordingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            binding.tvRecordingTimer.text = "00:00"
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewLifecycleOwner.lifecycleScope.launch {
            // Animasi dot berkedip
            var dotVisible = true
            while (isRecording) {
                recordingSeconds++
                val minutes = recordingSeconds / 60
                val seconds = recordingSeconds % 60
                binding.tvRecordingTimer.text =
                    String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                // Kedipkan dot
                dotVisible = !dotVisible
                binding.tvRecordingDot.visibility =
                    if (dotVisible) View.VISIBLE else View.INVISIBLE
                delay(1000L)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Map helpers
    // ─────────────────────────────────────────────────────────────────────────

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

    private fun updateMyLocationUI(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)

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

        accuracyCircle?.let { mapView.overlays.remove(it) }
        accuracyCircle = Polygon().apply {
            points = Polygon.pointsAsCircle(geoPoint, location.accuracy.toDouble())
            fillPaint.apply {
                style = android.graphics.Paint.Style.FILL
                color = 0x332196F3.toInt()
                isAntiAlias = true
            }
            outlinePaint.apply {
                style = android.graphics.Paint.Style.STROKE
                color = 0x882196F3.toInt()
                strokeWidth = 2f
                isAntiAlias = true
            }
        }
        accuracyCircle?.let { mapView.overlays.add(it) }

        myLocationMarker?.let { marker ->
            mapView.overlays.remove(marker)
            mapView.overlays.add(marker)
        }

        adjustZoomBasedOnAccuracy(location.accuracy)
        updateNavigationMode(location)
        mapView.invalidate()
    }

    private fun smoothFollow(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        if (lastCameraPoint == null) {
            mapView.controller.setCenter(geoPoint)
            lastCameraPoint = geoPoint
            return
        }
        val factor = when {
            location.speed < 3f -> 0.1
            location.speed < 10f -> 0.2
            else -> 0.3
        }
        val lat = lastCameraPoint!!.latitude +
                (geoPoint.latitude - lastCameraPoint!!.latitude) * factor
        val lon = lastCameraPoint!!.longitude +
                (geoPoint.longitude - lastCameraPoint!!.longitude) * factor
        val smoothPoint = GeoPoint(lat, lon)
        mapView.controller.setCenter(smoothPoint)
        lastCameraPoint = smoothPoint
    }

    private fun adjustZoomBasedOnAccuracy(accuracy: Float) {
        val targetZoom = when {
            accuracy < 5 -> 19.5
            accuracy < 10 -> 18.5
            accuracy < 20 -> 17.5
            accuracy < 50 -> 16.5
            else -> 15.5
        }
        if (abs(targetZoom - lastZoomLevel) > 0.5) {
            mapView.controller.setZoom(targetZoom)
            lastZoomLevel = targetZoom
        }
    }

    private fun updateNavigationMode(location: Location) {
        if (!isOrientationEnabled) return
        val rawBearing = location.bearing
        val smoothBearing = lastBearing + (rawBearing - lastBearing) * 0.2f
        lastBearing = smoothBearing
        mapView.setMapOrientation(-smoothBearing)
    }

    private fun clearMapOverlays() {
        mapView.overlays.remove(currentPolyline)
        pathPoints.clear()
        initCurrentPolyline()
        mapView.invalidate()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chart
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateChart(history: List<Float>) {
        if (!isChartVisible || history.isEmpty()) return

        val entries = history.mapIndexed { index, value ->
            Entry(index.toFloat(), value)
        }

        val lastVal = history.lastOrNull() ?: 0f
        val lineColor = getConditionColor(lastVal)

        val dataSet = LineDataSet(entries, getString(R.string.chart_z_label)).apply {
            color = lineColor
            setDrawCircles(false)
            lineWidth = 2.5f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillAlpha = 60
            fillColor = lineColor
            setDrawValues(false)
            highLightColor = Color.WHITE
        }

        // Update badge kondisi di header chart
        binding.tvChartConditionBadge.apply {
            text = when {
                lastVal < viewModel.thresholdBaik.value -> getString(R.string.condition_baik)
                lastVal < viewModel.thresholdSedang.value -> getString(R.string.condition_sedang)
                lastVal < viewModel.thresholdRusakRingan.value -> getString(R.string.condition_rusak_ringan)
                else -> "Rusak Berat"
            }
            setTextColor(lineColor)
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

    // ─────────────────────────────────────────────────────────────────────────
    // Survey dialogs
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleSurveyButtonClick() {
        when {
            !viewModel.isSurveying.value -> showStartSurveyDialog()
            viewModel.isPaused.value -> viewModel.resumeSurvey()
            else -> viewModel.pauseSurvey()
        }
    }

    private fun showStartSurveyDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_start_survey, null)
        val etSurveyor = view.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etSurveyorName
        )
        val etRoadName = view.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.etRoadName
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.start_survey))
            .setView(view)
            .setPositiveButton(getString(R.string.start)) { _, _ ->
                val surveyor = etSurveyor?.text?.toString()?.trim() ?: ""
                val roadName = etRoadName?.text?.toString()?.trim() ?: ""
                if (surveyor.isBlank()) {
                    showToast("Nama surveyor wajib diisi")
                    return@setPositiveButton
                }
                viewModel.startSurvey(surveyor, roadName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showConditionPicker() {
        val conditions = Condition.values()
        val currentCondition = viewModel.currentCondition.value
        val currentIndex = conditions.indexOf(currentCondition).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.pick_condition_title))
            .setSingleChoiceItems(
                conditions.map { it.name }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                val selected = conditions[which]
                if (!viewModel.checkConditionConsistency(selected)) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Peringatan")
                        .setMessage("Data getaran menunjukkan kondisi berbeda. Yakin memilih ${selected.name}?")
                        .setPositiveButton("Ya") { _, _ ->
                            viewModel.recordCondition(selected)
                            dialog.dismiss()
                        }
                        .setNegativeButton("Batal", null)
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
        val surfaces = Surface.values()
        val currentSurface = viewModel.currentSurface.value
        val currentIndex = surfaces.indexOf(currentSurface).coerceAtLeast(0)
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
                clearMapOverlays()
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

    // ─────────────────────────────────────────────────────────────────────────
    // UI state helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun showSurveyFABs(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        fabStop.visibility = visibility
        fabCamera.visibility = visibility
        fabVoice.visibility = visibility
        fabCondition.visibility = visibility
        fabSurface.visibility = visibility
        binding.chartContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateSurveyButton(isSurveying: Boolean, isPaused: Boolean) {
        fabSurvey.setImageResource(
            when {
                !isSurveying -> R.drawable.ic_play
                isPaused -> R.drawable.ic_play
                else -> R.drawable.ic_pause
            }
        )
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

    private fun restoreSurveyState() {
        val isSurveying = viewModel.isSurveying.value
        val isPaused = viewModel.isPaused.value
        updateSurveyButton(isSurveying, isPaused)
        if (isSurveying) {
            showSurveyFABs(true)
            isChartVisible = true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Observers
    // ─────────────────────────────────────────────────────────────────────────

    @OptIn(FlowPreview::class)
    private fun observeViewModel() {
        combine(viewModel.isSurveying, viewModel.isPaused) { surveying, paused ->
            surveying to paused
        }.onEach { (surveying, paused) ->
            updateSurveyButton(surveying, paused)
            showSurveyFABs(surveying)
            // Reset chart saat survey berhenti
            if (!surveying) {
                chart.clear()
                chart.setNoDataText(getString(R.string.chart_no_data))
                chart.invalidate()
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.location.onEach { location ->
            location?.let {
                try {
                    updateMyLocationUI(it)
                    if (isFollowingGPS) smoothFollow(it)
                } catch (e: Exception) {
                    Timber.e(e, "Error updating location UI")
                }
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

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
            binding.tvSpeed.text =
                getString(R.string.speed_format, (speedMs * 3.6f).toInt())
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.distance.onEach { dist ->
            binding.tvDistance.text = if (dist < 1000) {
                getString(R.string.distance_m_format, dist.toInt())
            } else {
                getString(R.string.distance_km_format, dist / 1000.0)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.currentCondition.onEach { condition ->
            binding.tvCondition.text = condition.name
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.currentSurface.onEach { surface ->
            binding.tvSurface.text = surface.name
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.vibrationHistory
            .sample(100L)
            .onEach { history ->
                try {
                    if (viewModel.isSurveying.value && !viewModel.isPaused.value) {
                        updateChart(history)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating chart")
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun observeDistanceTrigger() {
        viewModel.distanceTrigger.onEach { distance ->
            if (viewModel.isSurveying.value && !viewModel.isPaused.value) {
                Snackbar.make(
                    binding.root,
                    "Jarak ${distance.toInt()} m tercapai. Ambil foto?",
                    Snackbar.LENGTH_LONG
                ).setAction("Foto") {
                    openCamera()
                }.show()
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }
}