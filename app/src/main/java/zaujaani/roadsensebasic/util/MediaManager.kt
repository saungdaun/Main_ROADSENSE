package zaujaani.roadsensebasic.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import zaujaani.roadsensebasic.BuildConfig
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaManager — mengelola kamera dan rekaman suara.
 *
 * FIX:
 * - isRecording sekarang StateFlow → bisa diobservasi oleh Fragment untuk update UI FAB
 * - toggleRecording() untuk start/stop tanpa race condition
 * - Auto-stop rekaman setelah MAX_RECORDING_SECONDS
 * - Watermark foto sekarang menerima metadata (jarak, GPS, nama ruas)
 * - Tidak ada duplikasi resource: satu MediaRecorder aktif sekaligus
 */
class MediaManager(
    private val fragment: Fragment,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onPhotoTaken: (path: String, metadata: PhotoMetadata) -> Unit,
    private val onVoiceRecorded: (path: String) -> Unit,
    private val metadataProvider: () -> PhotoMetadata
) {
    companion object {
        const val MAX_RECORDING_SECONDS = 30
    }

    data class PhotoMetadata(
        val distanceMeters: Double = 0.0,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val roadName: String = "",
        val surveyMode: String = "GENERAL"
    )

    private var currentPhotoPath: String? = null
    private var currentPhotoUri: Uri? = null

    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingSeconds = MutableStateFlow(0)
    val recordingSeconds: StateFlow<Int> = _recordingSeconds.asStateFlow()

    private var recordingTimerJob: Job? = null

    // Camera launcher
    private val takePictureLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && currentPhotoPath != null) {
            val path = currentPhotoPath!!
            val meta = metadataProvider()
            lifecycleScope.launch(Dispatchers.IO) {
                val processedPath = addWatermark(path, meta)
                withContext(Dispatchers.Main) {
                    if (processedPath != null) {
                        lifecycleScope.launch(Dispatchers.IO) { saveToPublicGallery(processedPath) }
                        onPhotoTaken(processedPath, meta)
                        showToast("Foto tersimpan")
                    } else {
                        showToast("Gagal memproses foto")
                    }
                    currentPhotoPath = null
                    currentPhotoUri = null
                }
            }
        } else {
            showToast("Foto dibatalkan")
            currentPhotoPath = null
            currentPhotoUri = null
        }
    }

    private val cameraPermissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchCamera() else showToast("Izin kamera diperlukan") }

    private val micPermissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startRecording() else showToast("Izin mikrofon diperlukan") }

    // ── Public API ────────────────────────────────────────────────────────

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(
                fragment.requireContext(), Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Toggle: jika sedang recording → stop dan simpan.
     * Jika tidak → minta izin dan mulai recording.
     */
    fun toggleVoiceRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            if (ContextCompat.checkSelfPermission(
                    fragment.requireContext(), Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startRecording()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping recording")
        } finally {
            mediaRecorder = null
            _isRecording.value = false
            recordingTimerJob?.cancel()
            _recordingSeconds.value = 0
            currentAudioFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    onVoiceRecorded(file.absolutePath)
                    showToast("Rekaman tersimpan (${file.name})")
                } else {
                    showToast("File rekaman kosong, coba lagi")
                }
            }
            currentAudioFile = null
        }
    }

    fun release() {
        if (_isRecording.value) stopRecording()
        mediaRecorder?.release()
        recordingTimerJob?.cancel()
    }

    // ── Private: Camera ───────────────────────────────────────────────────

    private fun launchCamera() {
        try {
            val photoFile = createImageFile()
            val uri = FileProvider.getUriForFile(
                fragment.requireContext(),
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                photoFile
            )
            currentPhotoUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: IOException) {
            Timber.e(e, "Error creating photo file")
            showToast("Gagal membuat file foto")
        }
    }

    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = fragment.requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File(storageDir, "ROADSENSE_${timestamp}.jpg")
        currentPhotoPath = file.absolutePath
        return file
    }

    private suspend fun addWatermark(originalPath: String, meta: PhotoMetadata): String? =
        withContext(Dispatchers.IO) {
            try {
                val originalBitmap = BitmapFactory.decodeFile(originalPath) ?: return@withContext null
                val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutableBitmap)

                val bgPaint = Paint().apply {
                    color = Color.argb(140, 0, 0, 0)
                }
                canvas.drawRect(0f, 0f, mutableBitmap.width.toFloat(), 220f, bgPaint)

                val paint = Paint().apply {
                    color = Color.WHITE
                    textSize = 38f
                    isAntiAlias = true
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                }

                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val distStr = if (meta.distanceMeters < 1000) {
                    "STA: ${meta.distanceMeters.toInt()} m"
                } else {
                    "STA: ${String.format("%.2f", meta.distanceMeters / 1000)} km"
                }
                val gpsStr = "GPS: ${
                    String.format("%.6f", meta.latitude)
                }, ${String.format("%.6f", meta.longitude)}"
                val roadStr = if (meta.roadName.isNotBlank()) "Ruas: ${meta.roadName}" else "RoadSense"

                val lines = listOf("📍 $roadStr", "🕐 $dateStr", "📏 $distStr", "🌐 $gpsStr")
                var y = 52f
                lines.forEach { line ->
                    canvas.drawText(line, 30f, y, paint)
                    y += 52f
                }

                File(originalPath).outputStream().use { out ->
                    mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                originalBitmap.recycle()
                mutableBitmap.recycle()
                originalPath
            } catch (e: Exception) {
                Timber.e(e, "Gagal menambahkan watermark")
                null
            }
        }

    private suspend fun saveToPublicGallery(sourcePath: String) = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val displayName = "RoadSense_$timestamp.jpg"
            val sourceFile = File(sourcePath)
            val context = fragment.requireContext()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RoadSense")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { out -> sourceFile.inputStream().copyTo(out) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                }
            } else {
                val publicPictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val destDir = File(publicPictures, "RoadSense").apply { mkdirs() }
                sourceFile.copyTo(File(destDir, displayName), overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(File(destDir, displayName).absolutePath), arrayOf("image/jpeg"), null)
            }
        } catch (e: Exception) {
            Timber.e(e, "Gagal menyimpan foto ke galeri")
        }
    }

    // ── Private: Voice ────────────────────────────────────────────────────

    private fun startRecording() {
        if (_isRecording.value) return // Guard: jangan double start
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val audioDir = fragment.requireContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val audioFile = File(audioDir, "ROADSENSE_VOICE_${timestamp}.mp4")
            currentAudioFile = audioFile

            mediaRecorder = (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(fragment.requireContext())
                    } else {
                        @Suppress("DEPRECATION")
                        MediaRecorder()
                    }
                    ).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(128000)
                    setMaxDuration(MAX_RECORDING_SECONDS * 1000)
                    setOutputFile(audioFile.absolutePath)
                    setOnInfoListener { _, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                            Timber.d("Max recording duration reached, auto-stopping")
                            stopRecording()
                        }
                    }
                    prepare()
                    start()
                }

            _isRecording.value = true
            _recordingSeconds.value = 0
            startRecordingTimer()
            showToast("⏺ Merekam... (max ${MAX_RECORDING_SECONDS}s)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording")
            showToast("Gagal memulai rekaman: ${e.message}")
            mediaRecorder?.release()
            mediaRecorder = null
            currentAudioFile = null
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = lifecycleScope.launch {
            while (_isRecording.value) {
                delay(1000L)
                _recordingSeconds.value++
            }
        }
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(
            fragment.requireContext(), message, android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}