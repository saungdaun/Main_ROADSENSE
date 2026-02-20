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

class MediaManager(
    private val fragment: Fragment,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onPhotoTaken: (String) -> Unit,
    private val onVoiceRecorded: (String) -> Unit
) {
    private var currentPhotoPath: String? = null
    private var currentPhotoUri: Uri? = null

    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var isRecording = false
    private var recordingTimerJob: Job? = null
    private var recordingSeconds = 0

    // Camera launcher
    private val takePictureLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && currentPhotoPath != null) {
            val originalPath = currentPhotoPath!!
            lifecycleScope.launch(Dispatchers.IO) {
                val processedPath = addWatermark(originalPath)
                withContext(Dispatchers.Main) {
                    if (processedPath != null) {
                        saveToPublicGallery(processedPath)
                        onPhotoTaken(processedPath)
                        showToast("Foto tersimpan")
                    } else {
                        showToast("Gagal menambahkan watermark")
                    }
                    currentPhotoPath = null
                    currentPhotoUri = null
                }
            }
        } else {
            showToast("Gagal mengambil foto")
            currentPhotoPath = null
            currentPhotoUri = null
        }
    }

    private val cameraPermissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else showToast("Izin kamera diperlukan")
    }

    // Voice launcher
    private val micPermissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else showToast("Izin mikrofon diperlukan")
    }

    // Public methods
    fun openCamera() {
        if (ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun openVoiceRecorder() {
        if (ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
            }
            mediaRecorder = null
            isRecording = false
            recordingTimerJob?.cancel()
            if (currentAudioFile != null) {
                onVoiceRecorded(currentAudioFile!!.absolutePath)
                showToast("Rekaman tersimpan")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping recording")
        }
    }

    fun release() {
        if (isRecording) stopRecording()
        mediaRecorder?.release()
        recordingTimerJob?.cancel()
    }

    // Private methods
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

    private suspend fun addWatermark(originalPath: String): String? = withContext(Dispatchers.IO) {
        try {
            val originalBitmap = BitmapFactory.decodeFile(originalPath) ?: return@withContext null
            val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)
            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 40f
                isAntiAlias = true
                setShadowLayer(5f, 2f, 2f, Color.BLACK)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            var y = 80f
            // Text lines should be passed from outside; for now we use simple ones
            // In real usage, pass them as parameter
            listOf(
                "RoadSense Basic",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                "Jarak: 0 m" // placeholder
            ).forEach { line ->
                canvas.drawText(line, 50f, y, paint)
                y += 60f
            }
            File(originalPath).outputStream().use { out ->
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
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
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out -> sourceFile.inputStream().use { it.copyTo(out) } }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                val publicPictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val destDir = File(publicPictures, "RoadSense").apply { mkdirs() }
                val destFile = File(destDir, displayName)
                sourceFile.copyTo(destFile, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf("image/jpeg"), null)
            }
        } catch (e: Exception) {
            Timber.e(e, "Gagal menyimpan foto ke galeri")
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val audioDir = fragment.requireContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val audioFile = File(audioDir, "ROADSENSE_AUDIO_${timestamp}.mp4")
            currentAudioFile = audioFile
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(fragment.requireContext())
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
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
            startRecordingTimer()
        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording")
            showToast("Gagal memulai rekaman")
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = lifecycleScope.launch {
            while (isRecording) {
                recordingSeconds++
                delay(1000L)
            }
        }
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(fragment.requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }
}