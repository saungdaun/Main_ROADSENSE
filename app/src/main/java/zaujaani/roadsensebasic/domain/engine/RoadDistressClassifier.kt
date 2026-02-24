package zaujaani.roadsensebasic.domain.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import zaujaani.roadsensebasic.data.local.entity.DistressType
import zaujaani.roadsensebasic.data.local.entity.Severity
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RoadDistressClassifier — TFLite inference engine untuk deteksi kerusakan jalan.
 *
 * ## Model
 * MobileNetV3-Small fine-tuned pada Road Damage Dataset 2022 (RDD2022).
 * Model file: assets/road_distress_model.tflite
 * Labels file: assets/road_distress_labels.txt
 *
 * ## Cara mendapatkan model
 * Gunakan skrip training Python yang disertakan (tools/train_model.py):
 *   1. Download RDD2022 dataset dari: https://github.com/sekilab/RoadDamageDetector
 *   2. Jalankan: python tools/train_model.py --data_dir ./rdd2022 --output ./app/src/main/assets/
 *   3. Model akan otomatis di-export ke road_distress_model.tflite
 *
 * Alternatif: Gunakan model pre-trained dari ML Hub atau HuggingFace yang
 * compatible dengan format TFLite classification (input 224×224×3, output softmax).
 *
 * ## Label mapping (road_distress_labels.txt)
 * Line 0: CRACK
 * Line 1: POTHOLE
 * Line 2: RUTTING
 * Line 3: DEPRESSION
 * Line 4: SPALLING
 * Line 5: RAVELING
 * Line 6: NORMAL        ← jalan baik, tidak ada kerusakan signifikan
 * Line 7: INVALID       ← bukan foto jalan (langit, orang, kendaraan, dll)
 *
 * ## Input/Output
 * Input:  float32[1, 224, 224, 3]  — normalized [0.0, 1.0]
 * Output: float32[1, 8]            — softmax probabilities
 */
@Singleton
class RoadDistressClassifier @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val MODEL_FILE   = "road_distress_model.tflite"
        private const val LABELS_FILE  = "road_distress_labels.txt"
        private const val INPUT_SIZE   = 224
        private const val NUM_CHANNELS = 3
        private const val NUM_BYTES_PER_CHANNEL = 4   // float32

        // Threshold: confidence di bawah ini dianggap "tidak yakin"
        private const val CONFIDENCE_THRESHOLD   = 0.45f
        // Confidence di bawah ini untuk class INVALID = photo bukan jalan
        private const val INVALID_THRESHOLD      = 0.60f
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    /** true jika model berhasil dimuat dari assets */
    val isReady: Boolean get() = interpreter != null && labels.isNotEmpty()

    /**
     * Muat model dari assets. Dipanggil sekali saat app start (via DI atau lazy).
     * Idempotent — aman dipanggil berkali-kali.
     */
    fun initialize() {
        if (isReady) return
        try {
            val modelBuffer = loadModelFromAssets()
            val options = Interpreter.Options().apply {
                numThreads = 2          // 2 thread = balance kecepatan vs baterai
                useNNAPI   = true       // gunakan Neural Networks API jika tersedia
            }
            interpreter = Interpreter(modelBuffer, options)
            labels      = loadLabelsFromAssets()
            Timber.i("RoadDistressClassifier: model loaded, ${labels.size} classes")
        } catch (e: Exception) {
            Timber.e(e, "RoadDistressClassifier: gagal memuat model dari assets/$MODEL_FILE")
            interpreter = null
        }
    }

    /**
     * Hasil klasifikasi satu foto.
     *
     * @param topType        Jenis kerusakan dominan (null jika NORMAL/INVALID)
     * @param topTypeLabel   Label string mentah dari model ("CRACK", "POTHOLE", dll)
     * @param confidence     0.0–1.0, seberapa yakin model
     * @param severity       Estimasi severity berdasarkan confidence score
     * @param conditionScore 0–100, semakin tinggi semakin rusak
     * @param isValid        false jika foto bukan jalan (INVALID class)
     * @param isNormal       true jika tidak ada kerusakan signifikan
     * @param allScores      Skor lengkap semua kelas untuk debugging/export
     * @param description    Deskripsi singkat dalam Bahasa Indonesia
     * @param recommendation Rekomendasi penanganan singkat
     */
    data class ClassificationResult(
        val topType: DistressType?,
        val topTypeLabel: String,
        val confidence: Float,
        val severity: Severity,
        val conditionScore: Int,
        val isValid: Boolean,
        val isNormal: Boolean,
        val allScores: Map<String, Float>,
        val description: String,
        val recommendation: String
    )

    /**
     * Klasifikasi satu foto dari path file.
     *
     * @param photoPath Path absolut file foto
     * @return ClassificationResult, atau null jika file tidak bisa dibaca / model belum siap
     */
    fun classify(photoPath: String): ClassificationResult? {
        if (!isReady) {
            Timber.w("classify() dipanggil sebelum initialize()")
            return null
        }

        val file = File(photoPath)
        if (!file.exists() || file.length() == 0L) {
            Timber.w("File tidak ada: $photoPath")
            return null
        }

        return try {
            val bitmap = loadAndPrepareBitmap(file) ?: return null
            val result = runInference(bitmap)
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Timber.e(e, "classify() error: $photoPath")
            null
        }
    }

    // ── Preprocessing ─────────────────────────────────────────────────────

    private fun loadAndPrepareBitmap(file: File): Bitmap? {
        // Decode dengan downsampling agar hemat RAM
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
            val maxDim = maxOf(outWidth, outHeight)
            inSampleSize   = if (maxDim > 512) 2 else 1
            inJustDecodeBounds = false
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        // Resize ke INPUT_SIZE × INPUT_SIZE
        val resized = Bitmap.createScaledBitmap(raw, INPUT_SIZE, INPUT_SIZE, true)
        if (resized !== raw) raw.recycle()
        return resized
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(
            1 * INPUT_SIZE * INPUT_SIZE * NUM_CHANNELS * NUM_BYTES_PER_CHANNEL
        ).apply { order(ByteOrder.nativeOrder()) }

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            // Normalize: R, G, B → [0.0, 1.0]
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr  8) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel       ) and 0xFF) / 255.0f)
        }

        buffer.rewind()
        return buffer
    }

    // ── Inference ─────────────────────────────────────────────────────────

    private fun runInference(bitmap: Bitmap): ClassificationResult {
        val inputBuffer  = bitmapToByteBuffer(bitmap)
        val outputBuffer = Array(1) { FloatArray(labels.size) }

        interpreter!!.run(inputBuffer, outputBuffer)

        val scores = outputBuffer[0]
        val scoreMap = labels.mapIndexed { i, label ->
            label to (scores.getOrNull(i) ?: 0f)
        }.toMap()

        return buildResult(scoreMap)
    }

    // ── Result building ───────────────────────────────────────────────────

    private fun buildResult(scores: Map<String, Float>): ClassificationResult {
        val invalidScore = scores["INVALID"] ?: 0f
        val normalScore  = scores["NORMAL"]  ?: 0f

        // Foto bukan jalan sama sekali
        if (invalidScore >= INVALID_THRESHOLD) {
            return ClassificationResult(
                topType        = null,
                topTypeLabel   = "INVALID",
                confidence     = invalidScore,
                severity       = Severity.LOW,
                conditionScore = 0,
                isValid        = false,
                isNormal       = false,
                allScores      = scores,
                description    = "Bukan foto permukaan jalan",
                recommendation = "Gunakan foto yang memperlihatkan permukaan jalan"
            )
        }

        // Filter hanya kerusakan (bukan NORMAL / INVALID)
        val distressScores = scores.filterKeys { it != "NORMAL" && it != "INVALID" }
        val topEntry       = distressScores.maxByOrNull { it.value }
        val topLabel       = topEntry?.key ?: "NORMAL"
        val topScore       = topEntry?.value ?: 0f

        // Dominasi NORMAL atau confidence terlalu rendah = jalan baik
        if (normalScore > topScore || topScore < CONFIDENCE_THRESHOLD) {
            return ClassificationResult(
                topType        = null,
                topTypeLabel   = "NORMAL",
                confidence     = normalScore.coerceAtLeast(1f - topScore),
                severity       = Severity.LOW,
                conditionScore = (topScore * 20).toInt().coerceIn(0, 20),
                isValid        = true,
                isNormal       = true,
                allScores      = scores,
                description    = "Kondisi jalan baik, tidak ada kerusakan signifikan",
                recommendation = "Pemeliharaan rutin"
            )
        }

        val distressType   = labelToDistressType(topLabel)
        val severity       = scoreToSeverity(topScore)
        val conditionScore = buildConditionScore(topScore, severity)

        // Ambil 2 tipe kerusakan teratas untuk deskripsi yang lebih kaya
        val top2 = distressScores.entries.sortedByDescending { it.value }.take(2)
        val secondType = if (top2.size > 1 && top2[1].value > CONFIDENCE_THRESHOLD * 0.7f)
            top2[1].key else null

        return ClassificationResult(
            topType        = distressType,
            topTypeLabel   = topLabel,
            confidence     = topScore,
            severity       = severity,
            conditionScore = conditionScore,
            isValid        = true,
            isNormal       = false,
            allScores      = scores,
            description    = buildDescription(topLabel, severity, secondType),
            recommendation = buildRecommendation(distressType, severity)
        )
    }

    // ── Mapping helpers ───────────────────────────────────────────────────

    private fun labelToDistressType(label: String): DistressType? = when (label.uppercase()) {
        "CRACK"      -> DistressType.CRACK
        "POTHOLE"    -> DistressType.POTHOLE
        "RUTTING"    -> DistressType.RUTTING
        "DEPRESSION" -> DistressType.DEPRESSION
        "SPALLING"   -> DistressType.SPALLING
        "RAVELING"   -> DistressType.RAVELING
        else         -> DistressType.OTHER
    }

    /** Confidence → severity: low <0.6, medium <0.80, high ≥0.80 */
    private fun scoreToSeverity(score: Float): Severity = when {
        score >= 0.80f -> Severity.HIGH
        score >= 0.60f -> Severity.MEDIUM
        else           -> Severity.LOW
    }

    /** Condition score 0–100 berdasarkan distress type + severity */
    private fun buildConditionScore(confidence: Float, severity: Severity): Int {
        val base = (confidence * 100).toInt()
        return when (severity) {
            Severity.HIGH   -> (base * 0.9).toInt().coerceIn(60, 100)
            Severity.MEDIUM -> (base * 0.6).toInt().coerceIn(30, 65)
            Severity.LOW    -> (base * 0.4).toInt().coerceIn(10, 40)
        }
    }

    private fun buildDescription(
        topLabel: String,
        severity: Severity,
        secondLabel: String?
    ): String {
        val sevText = when (severity) {
            Severity.HIGH   -> "parah"
            Severity.MEDIUM -> "sedang"
            Severity.LOW    -> "ringan"
        }
        val typeText = when (topLabel.uppercase()) {
            "CRACK"      -> "Retak"
            "POTHOLE"    -> "Lubang"
            "RUTTING"    -> "Alur / Rutting"
            "DEPRESSION" -> "Amblas"
            "SPALLING"   -> "Pengelupasan"
            "RAVELING"   -> "Lepas Agregat"
            else         -> "Kerusakan"
        }
        val second = if (secondLabel != null) " disertai ${secondLabel.lowercase()}" else ""
        return "$typeText $sevText terdeteksi$second"
    }

    private fun buildRecommendation(type: DistressType?, severity: Severity): String {
        if (type == null) return "Pemeliharaan rutin"
        return when {
            severity == Severity.HIGH -> when (type) {
                DistressType.POTHOLE    -> "Penambalan segera — bahaya kecelakaan"
                DistressType.CRACK      -> "Peretakan lebar — perlu overlay atau rekonstruksi"
                DistressType.RUTTING    -> "Alur dalam — rekonstruksi lapisan"
                DistressType.DEPRESSION -> "Amblas kritis — rekonstruksi pondasi"
                else                   -> "Penanganan segera diperlukan"
            }
            severity == Severity.MEDIUM -> when (type) {
                DistressType.POTHOLE    -> "Penambalan terencana"
                DistressType.CRACK      -> "Pengisian retakan / crack sealing"
                DistressType.RUTTING    -> "Rehabilitasi permukaan"
                DistressType.DEPRESSION -> "Leveling dan perbaikan pondasi"
                else                   -> "Pemeliharaan berkala"
            }
            else -> "Pemeliharaan rutin — pantau perkembangan"
        }
    }

    // ── Asset loading ─────────────────────────────────────────────────────

    private fun loadModelFromAssets(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream    = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel    = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    private fun loadLabelsFromAssets(): List<String> =
        context.assets.open(LABELS_FILE)
            .bufferedReader()
            .readLines()
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}