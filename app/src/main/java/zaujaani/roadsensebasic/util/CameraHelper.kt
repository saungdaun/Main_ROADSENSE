package zaujaani.roadsensebasic.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private val cameraProviderFuture by lazy {
        ProcessCameraProvider.getInstance(context)
    }

    fun startCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        imageCapture = ImageCapture.Builder().build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun takePhoto(onImageSaved: (Uri) -> Unit, onError: (ImageCaptureException) -> Unit) {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RoadSense")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    outputFileResults.savedUri?.let { uri ->
                        onImageSaved(uri)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}