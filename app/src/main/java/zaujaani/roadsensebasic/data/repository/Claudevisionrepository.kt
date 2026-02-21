package zaujaani.roadsensebasic.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import zaujaani.roadsensebasic.data.local.entity.AnalysisStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ClaudeVisionRepository — Tahap 2: Komunikasi dengan Anthropic Vision API
 *
 * Tanggung jawab kelas ini:
 *   1. Compress & encode foto ke base64 (hemat bandwidth)
 *   2. Susun request JSON sesuai Anthropic Messages API
 *   3. Parse response JSON → AnalysisResult
 *   4. Retry otomatis dengan exponential backoff
 *   5. Validasi foto sebelum dikirim (ukuran, bisa dibaca)
 *
 * Kelas ini TIDAK tahu soal DB — semua penyimpanan dilakukan
 * oleh PhotoAnalysisRepository yang memanggilnya.
 */
@Singleton
class ClaudeVisionRepository @Inject constructor(
    private val photoAnalysisRepository: PhotoAnalysisRepository
) {

    companion object {
        // Ganti dengan API key kamu — idealnya dari BuildConfig atau EncryptedSharedPreferences
        // JANGAN hardcode di produksi!
        private const val API_KEY       = "YOUR_ANTHROPIC_API_KEY"
        private const val API_URL       = "https://api.anthropic.com/v1/messages"
        private const val MODEL         = "claude-opus-4-6"
        private const val MAX_TOKENS    = 1024
        private const val TIMEOUT_MS    = 30_000        // 30 detik

        private const val MAX_RETRIES   = 3
        private const val RETRY_DELAY   = 2_000L        // 2 detik base delay

        // Resize foto sebelum dikirim — hemat bandwidth & lebih cepat
        private const val MAX_IMAGE_DIM = 1024          // pixel
        private const val JPEG_QUALITY  = 75            // % kualitas JPEG
        private const val MAX_FILE_SIZE_KB = 500        // batas reject file terlalu kecil/corrupt
    }

    /**
     * Data class hasil parsing response Claude API.
     * Ini yang diserahkan ke PhotoAnalysisRepository untuk disimpan ke DB.
     */
    data class AnalysisResult(
        val overallCondition: String,       // BAIK | SEDANG | RUSAK_RINGAN | RUSAK_BERAT
        val overallConditionScore: Int,     // 0–100
        val detectedTypes: String,          // "POTHOLE,CRACK"
        val dominantSeverity: String,       // LOW | MEDIUM | HIGH
        val confidenceScore: Float,         // 0.0–1.0
        val aiDescription: String,          // narasi Indonesia
        val aiRecommendation: String,       // rekomendasi penanganan
        val rawJson: String                 // raw JSON dari API (untuk debug)
    )

    /**
     * Analisis satu foto — entry point utama.
     *
     * Flow:
     *   1. Validasi file
     *   2. Compress & encode
     *   3. Kirim ke API (dengan retry)
     *   4. Parse response
     *   5. Kembalikan AnalysisResult atau null jika gagal
     *
     * Status DB diupdate di dalam fungsi ini via PhotoAnalysisRepository.
     */
    suspend fun analyzePhoto(photoPath: String): AnalysisResult? {
        return withContext(Dispatchers.IO) {

            // Step 1: validasi file
            val file = File(photoPath)
            if (!file.exists() || file.length() == 0L) {
                Timber.w("File tidak ada atau kosong: $photoPath")
                photoAnalysisRepository.markAsSkipped(photoPath, "File tidak ditemukan")
                return@withContext null
            }

            // Step 2: compress & encode ke base64
            val base64 = try {
                encodePhotoToBase64(file)
            } catch (e: Exception) {
                Timber.e(e, "Gagal encode foto: $photoPath")
                photoAnalysisRepository.markAsSkipped(photoPath, "Foto tidak bisa dibaca: ${e.message}")
                return@withContext null
            }

            if (base64 == null) {
                photoAnalysisRepository.markAsSkipped(photoPath, "Foto corrupt atau terlalu kecil")
                return@withContext null
            }

            // Step 3: kirim ke API dengan retry
            photoAnalysisRepository.markAsAnalyzing(photoPath)

            var lastError = ""
            repeat(MAX_RETRIES) { attempt ->
                try {
                    val responseJson = callClaudeApi(base64)
                    val result = parseApiResponse(responseJson)

                    // Step 5: simpan ke DB
                    photoAnalysisRepository.saveAnalysisResult(
                        photoPath             = photoPath,
                        analysisJson          = result.rawJson,
                        overallCondition      = result.overallCondition,
                        overallConditionScore = result.overallConditionScore,
                        detectedTypes         = result.detectedTypes,
                        dominantSeverity      = result.dominantSeverity,
                        confidenceScore       = result.confidenceScore,
                        aiDescription         = result.aiDescription,
                        aiRecommendation      = result.aiRecommendation
                    )
                    Timber.d("Analisis berhasil: $photoPath → ${result.overallCondition}")
                    return@withContext result

                } catch (e: Exception) {
                    lastError = e.message ?: "Unknown error"
                    Timber.w("Attempt ${attempt + 1}/$MAX_RETRIES gagal: $lastError")
                    if (attempt < MAX_RETRIES - 1) {
                        // Exponential backoff: 2s, 4s, 8s
                        delay(RETRY_DELAY * (1L shl attempt))
                    }
                }
            }

            // Semua retry habis
            Timber.e("Analisis gagal setelah $MAX_RETRIES percobaan: $photoPath — $lastError")
            photoAnalysisRepository.markAsFailed(photoPath, lastError)
            null
        }
    }

    // ── Step 2: Encode foto ───────────────────────────────────────────────

    private fun encodePhotoToBase64(file: File): String? {
        // Decode dengan sampling — hemat RAM untuk foto besar
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
            inSampleSize = calculateInSampleSize(outWidth, outHeight, MAX_IMAGE_DIM, MAX_IMAGE_DIM)
            inJustDecodeBounds = false
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: return null

        // Resize kalau masih terlalu besar setelah sampling
        val resized = if (bitmap.width > MAX_IMAGE_DIM || bitmap.height > MAX_IMAGE_DIM) {
            val ratio = MAX_IMAGE_DIM.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true
            )
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)

        if (resized != bitmap) resized.recycle()
        bitmap.recycle()

        val bytes = out.toByteArray()
        Timber.d("Foto di-encode: ${bytes.size / 1024} KB")

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (height > reqH || width > reqW) {
            val halfH = height / 2
            val halfW = width / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ── Step 3: Panggil Claude API ────────────────────────────────────────

    private fun callClaudeApi(base64Image: String): String {
        val prompt = buildPrompt()
        val requestBody = buildRequestJson(base64Image, prompt)

        val url  = URL(API_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod   = "POST"
            connectTimeout  = TIMEOUT_MS
            readTimeout     = TIMEOUT_MS
            doOutput        = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", API_KEY)
            setRequestProperty("anthropic-version", "2023-06-01")
        }

        conn.outputStream.use { os ->
            os.write(requestBody.toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        val responseText = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val errText = conn.errorStream?.bufferedReader()?.readText() ?: "No error body"
            throw Exception("HTTP $responseCode: $errText")
        }
        conn.disconnect()

        Timber.d("API response code: $responseCode, length: ${responseText.length}")
        return responseText
    }

    /**
     * Prompt yang dikirim ke Claude.
     *
     * Prompt dirancang untuk:
     * - Output HANYA JSON (tidak ada teks lain)
     * - Bahasa Indonesia untuk deskripsi
     * - Nilai sesuai enum yang dipakai di app (BAIK, SEDANG, dll)
     * - Confidence score yang jujur (AI tidak oversell)
     */
    private fun buildPrompt(): String = """
        Kamu adalah asisten analisis kondisi jalan untuk aplikasi survey jalan RoadSense Indonesia.
        
        Analisis foto permukaan jalan ini dan identifikasi kerusakan yang ada.
        
        Balas HANYA dengan JSON valid berikut ini, tanpa teks apapun di luar JSON:
        
        {
          "detected": true/false,
          "overall_condition": "BAIK" atau "SEDANG" atau "RUSAK_RINGAN" atau "RUSAK_BERAT",
          "overall_condition_score": angka 0-100 (0=sempurna, 100=rusak total),
          "distress_types": ["POTHOLE", "CRACK", "RUTTING", "DEPRESSION", "SPALLING", "RAVELING"],
          "dominant_severity": "LOW" atau "MEDIUM" atau "HIGH",
          "confidence": angka 0.0-1.0 seberapa yakin kamu,
          "description": "deskripsi singkat kondisi jalan dalam Bahasa Indonesia, maksimal 100 karakter",
          "recommendation": "rekomendasi penanganan singkat dalam Bahasa Indonesia, maksimal 80 karakter"
        }
        
        Aturan:
        - Jika foto buram, gelap, atau bukan foto jalan: set detected=false, confidence rendah
        - overall_condition_score: BAIK=0-20, SEDANG=21-50, RUSAK_RINGAN=51-75, RUSAK_BERAT=76-100
        - distress_types: array kosong [] jika tidak ada kerusakan
        - dominant_severity: LOW jika ringan, MEDIUM jika sedang, HIGH jika parah
        - confidence: rendah (<0.5) jika foto tidak jelas, tinggi (>0.8) jika jelas
        - description dan recommendation: Bahasa Indonesia, singkat dan teknis
    """.trimIndent()

    private fun buildRequestJson(base64Image: String, prompt: String): String {
        val imageContent = JSONObject().apply {
            put("type", "image")
            put("source", JSONObject().apply {
                put("type", "base64")
                put("media_type", "image/jpeg")
                put("data", base64Image)
            })
        }
        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        }
        val message = JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(imageContent)
                put(textContent)
            })
        }
        return JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("messages", JSONArray().apply { put(message) })
        }.toString()
    }

    // ── Step 4: Parse response ────────────────────────────────────────────

    /**
     * Parse response dari API ke AnalysisResult.
     *
     * Response Claude berformat:
     * {
     *   "content": [{ "type": "text", "text": "{ ...JSON kita... }" }],
     *   ...
     * }
     *
     * Kita ambil text dari content[0], lalu parse sebagai JSON lagi.
     */
    private fun parseApiResponse(responseJson: String): AnalysisResult {
        val root     = JSONObject(responseJson)
        val content  = root.getJSONArray("content")
        val rawText  = content.getJSONObject(0).getString("text").trim()

        // Bersihkan kalau AI membungkus dengan ```json ... ```
        val cleanJson = rawText
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val j = JSONObject(cleanJson)

        val detected          = j.optBoolean("detected", false)
        val overallCondition  = if (detected)
            j.optString("overall_condition", "SEDANG").uppercase()
        else
            "TIDAK_TERIDENTIFIKASI"

        val conditionScore    = j.optInt("overall_condition_score", 0)
            .coerceIn(0, 100)

        val typesArray        = j.optJSONArray("distress_types")
        val detectedTypes     = buildString {
            if (typesArray != null) {
                for (i in 0 until typesArray.length()) {
                    if (i > 0) append(",")
                    append(typesArray.getString(i).uppercase())
                }
            }
        }

        val dominantSeverity  = j.optString("dominant_severity", "LOW").uppercase()
        val confidence        = j.optDouble("confidence", 0.5).toFloat()
            .coerceIn(0f, 1f)
        val description       = j.optString("description", "").take(150)
        val recommendation    = j.optString("recommendation", "").take(120)

        return AnalysisResult(
            overallCondition      = overallCondition,
            overallConditionScore = conditionScore,
            detectedTypes         = detectedTypes,
            dominantSeverity      = dominantSeverity,
            confidenceScore       = confidence,
            aiDescription         = description,
            aiRecommendation      = recommendation,
            rawJson               = cleanJson
        )
    }
}