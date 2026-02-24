package zaujaani.roadsensebasic.ui.analysis

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsensebasic.data.local.entity.AnalysisStatus
import zaujaani.roadsensebasic.data.local.entity.PhotoAnalysisResult
import zaujaani.roadsensebasic.data.repository.PhotoAnalysisRepository
import zaujaani.roadsensebasic.domain.engine.RoadDistressClassifier
import zaujaani.roadsensebasic.ui.summary.PhotoItem
import javax.inject.Inject

/**
 * PhotoAnalysisViewModel
 *
 * Mengorkestrasi analisis foto post-survey:
 * - Single photo: klik satu foto → analisis langsung
 * - Batch: "Analisis Semua" → proses antrian satu per satu, bisa di-cancel
 * - Override manual: surveyor bisa koreksi hasil AI
 * - Save: simpan ke DB via PhotoAnalysisRepository
 *
 * Model TFLite dijalankan di IO dispatcher — UI tetap responsif.
 */
@HiltViewModel
class PhotoAnalysisViewModel @Inject constructor(
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val classifier: RoadDistressClassifier
) : ViewModel() {

    // ── State: daftar foto + hasil analisis ───────────────────────────────

    /** Semua hasil analisis untuk session yang sedang dibuka */
    private val _analysisResults = MutableLiveData<List<PhotoAnalysisResult>>(emptyList())
    val analysisResults: LiveData<List<PhotoAnalysisResult>> = _analysisResults

    /** Daftar PhotoItem (dari ViewModel Summary, di-set saat navigasi ke Fragment ini) */
    private val _photos = MutableLiveData<List<PhotoItem>>(emptyList())
    val photos: LiveData<List<PhotoItem>> = _photos

    // ── State: progress batch ─────────────────────────────────────────────

    data class BatchProgress(
        val current: Int,
        val total: Int,
        val currentPhotoLabel: String = ""
    ) {
        val percent: Int get() = if (total > 0) (current * 100 / total) else 0
        val isDone: Boolean get() = current >= total
    }

    private val _batchProgress = MutableLiveData<BatchProgress?>(null)
    val batchProgress: LiveData<BatchProgress?> = _batchProgress

    private val _isBatchRunning = MutableLiveData(false)
    val isBatchRunning: LiveData<Boolean> = _isBatchRunning

    // ── State: analisis tunggal ───────────────────────────────────────────

    private val _singleAnalyzing = MutableLiveData<String?>(null) // photoPath yang sedang dianalisis
    val singleAnalyzing: LiveData<String?> = _singleAnalyzing

    // ── State: error / event ──────────────────────────────────────────────

    private val _errorEvent = MutableLiveData<String?>(null)
    val errorEvent: LiveData<String?> = _errorEvent

    // ── Internals ─────────────────────────────────────────────────────────

    private var batchJob: Job? = null
    private var currentSessionId: Long = -1L

    // ── Setup ─────────────────────────────────────────────────────────────

    /**
     * Dipanggil dari Fragment saat navigasi ke halaman ini.
     * @param photos  List foto dari SessionDetailUi.allPhotos
     * @param sessionId  ID session untuk query DB
     */
    fun loadSession(photos: List<PhotoItem>, sessionId: Long) {
        currentSessionId = sessionId
        _photos.value   = photos

        // Pastikan model sudah dimuat
        if (!classifier.isReady) {
            classifier.initialize()
        }

        // Observe hasil analisis dari DB
        viewModelScope.launch {
            try {
                photoAnalysisRepository.getAnalysisResultsForSession(sessionId)
                    .collectLatest { results ->
                        _analysisResults.value = results
                    }
            } catch (e: Exception) {
                Timber.e(e, "Gagal observe analysis results")
            }
        }
    }

    // ── Single photo analysis ─────────────────────────────────────────────

    /**
     * Analisis satu foto.
     * Jika sudah DONE dan bukan FAILED → skip (tidak analisis ulang).
     * Surveyor bisa paksa re-analisis dengan [forceReanalyze].
     */
    fun analyzeSingle(photoItem: PhotoItem, forceReanalyze: Boolean = false) {
        viewModelScope.launch {
            val existing = photoAnalysisRepository.getResultByPath(photoItem.path)

            // Skip jika sudah selesai dan tidak dipaksa
            if (!forceReanalyze &&
                existing?.status == AnalysisStatus.DONE) {
                Timber.d("analyzeSingle: skip DONE photo ${photoItem.path}")
                return@launch
            }

            if (!classifier.isReady) {
                _errorEvent.value = "Model belum siap. Coba tutup dan buka kembali halaman ini."
                return@launch
            }

            _singleAnalyzing.value = photoItem.path

            try {
                // Daftarkan ke DB sebagai ANALYZING
                photoAnalysisRepository.enqueuePhotosForSession(
                    currentSessionId, listOf(photoItem.path)
                )
                photoAnalysisRepository.markAsAnalyzing(photoItem.path)

                // Jalankan inference (sudah di IO dispatcher via Repository)
                val result = classifier.classify(photoItem.path)

                if (result == null) {
                    photoAnalysisRepository.markAsFailed(photoItem.path, "Foto tidak bisa diproses")
                    _errorEvent.value = "Foto tidak bisa dianalisis — pastikan file tidak corrupt"
                    return@launch
                }

                if (!result.isValid) {
                    photoAnalysisRepository.markAsSkipped(photoItem.path, "Bukan foto permukaan jalan")
                    Timber.d("Photo INVALID (bukan jalan): ${photoItem.path}")
                    return@launch
                }

                // Simpan hasil ke DB
                photoAnalysisRepository.saveAnalysisResult(
                    photoPath             = photoItem.path,
                    analysisJson          = buildAnalysisJson(result),
                    overallCondition      = if (result.isNormal) "BAIK" else result.topTypeLabel,
                    overallConditionScore = result.conditionScore,
                    detectedTypes         = result.topType?.name ?: "",
                    dominantSeverity      = result.severity.name,
                    confidenceScore       = result.confidence,
                    aiDescription         = result.description,
                    aiRecommendation      = result.recommendation
                )

                Timber.d("analyzeSingle DONE: ${photoItem.path} → ${result.topTypeLabel} ${result.severity}")

            } catch (e: Exception) {
                Timber.e(e, "analyzeSingle error: ${photoItem.path}")
                photoAnalysisRepository.markAsFailed(photoItem.path, e.message ?: "Error tidak diketahui")
                _errorEvent.value = "Analisis gagal: ${e.message?.take(60)}"
            } finally {
                _singleAnalyzing.value = null
            }
        }
    }

    // ── Batch analysis ────────────────────────────────────────────────────

    /**
     * Analisis semua foto yang belum DONE secara berurutan.
     * Menampilkan progress per foto.
     * Bisa di-cancel dengan [cancelBatch].
     */
    fun analyzeAll() {
        if (_isBatchRunning.value == true) return
        if (!classifier.isReady) {
            _errorEvent.value = "Model belum siap. Tunggu sebentar dan coba lagi."
            return
        }

        val photosToAnalyze = _photos.value
            ?.filter { photo ->
                val existing = _analysisResults.value?.find { it.photoPath == photo.path }
                existing == null || existing.status in listOf(
                    AnalysisStatus.PENDING,
                    AnalysisStatus.FAILED
                )
            }
            ?: emptyList()

        if (photosToAnalyze.isEmpty()) {
            _errorEvent.value = "Semua foto sudah dianalisis"
            return
        }

        batchJob = viewModelScope.launch {
            _isBatchRunning.value = true
            _batchProgress.value  = BatchProgress(0, photosToAnalyze.size)

            // Daftarkan semua ke antrian
            photoAnalysisRepository.enqueuePhotosForSession(
                currentSessionId,
                photosToAnalyze.map { it.path }
            )

            photosToAnalyze.forEachIndexed { idx, photo ->
                if (!_isBatchRunning.value!!) return@launch  // cancelled

                _batchProgress.value = BatchProgress(
                    current            = idx,
                    total              = photosToAnalyze.size,
                    currentPhotoLabel  = photo.label.take(30)
                )

                try {
                    photoAnalysisRepository.markAsAnalyzing(photo.path)
                    val result = classifier.classify(photo.path)

                    if (result == null || !result.isValid) {
                        val reason = if (result == null) "Foto tidak bisa diproses"
                        else "Bukan foto permukaan jalan"
                        photoAnalysisRepository.markAsSkipped(photo.path, reason)
                    } else {
                        photoAnalysisRepository.saveAnalysisResult(
                            photoPath             = photo.path,
                            analysisJson          = buildAnalysisJson(result),
                            overallCondition      = if (result.isNormal) "BAIK" else result.topTypeLabel,
                            overallConditionScore = result.conditionScore,
                            detectedTypes         = result.topType?.name ?: "",
                            dominantSeverity      = result.severity.name,
                            confidenceScore       = result.confidence,
                            aiDescription         = result.description,
                            aiRecommendation      = result.recommendation
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Batch analyze error: ${photo.path}")
                    photoAnalysisRepository.markAsFailed(photo.path, e.message ?: "Error")
                }
            }

            _batchProgress.value  = BatchProgress(photosToAnalyze.size, photosToAnalyze.size)
            _isBatchRunning.value = false
            Timber.d("analyzeAll selesai: ${photosToAnalyze.size} foto")
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
        _isBatchRunning.value = false
        _batchProgress.value  = null
        Timber.d("Batch analisis dibatalkan")
    }

    // ── Manual override ───────────────────────────────────────────────────

    fun saveManualOverride(photoPath: String, condition: String, notes: String) {
        viewModelScope.launch {
            try {
                photoAnalysisRepository.saveManualOverride(photoPath, condition, notes)
                Timber.d("Override saved: $photoPath → $condition")
            } catch (e: Exception) {
                _errorEvent.value = "Gagal menyimpan koreksi: ${e.message}"
            }
        }
    }

    fun clearManualOverride(photoPath: String) {
        viewModelScope.launch {
            photoAnalysisRepository.clearManualOverride(photoPath)
        }
    }

    // ── Retry ─────────────────────────────────────────────────────────────

    fun retryFailed() {
        viewModelScope.launch {
            photoAnalysisRepository.retryFailed(currentSessionId)
            analyzeAll()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    fun clearError() { _errorEvent.value = null }

    /** Mapping result → JSON sederhana untuk field analysisJson di DB */
    private fun buildAnalysisJson(result: RoadDistressClassifier.ClassificationResult): String {
        val scoresStr = result.allScores.entries
            .sortedByDescending { it.value }
            .joinToString(",") { "\"${it.key}\":${String.format("%.3f", it.value)}" }
        return """{
  "engine":"tflite_on_device",
  "topClass":"${result.topTypeLabel}",
  "confidence":${String.format("%.3f", result.confidence)},
  "severity":"${result.severity.name}",
  "conditionScore":${result.conditionScore},
  "isValid":${result.isValid},
  "isNormal":${result.isNormal},
  "allScores":{$scoresStr}
}"""
    }

    override fun onCleared() {
        super.onCleared()
        batchJob?.cancel()
    }
}