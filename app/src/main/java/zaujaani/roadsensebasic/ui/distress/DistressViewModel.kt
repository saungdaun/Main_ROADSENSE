package zaujaani.roadsensebasic.ui.distress

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsensebasic.data.local.entity.DistressType
import zaujaani.roadsensebasic.data.local.entity.Severity
import zaujaani.roadsensebasic.data.repository.PhotoAnalysisRepository
import zaujaani.roadsensebasic.domain.engine.SurveyEngine
import zaujaani.roadsensebasic.domain.model.LocationData
import javax.inject.Inject

// ── SaveResult — sealed class untuk one-shot event ke UI ─────────────────

sealed class SaveResult {
    object Loading : SaveResult()
    object Success : SaveResult()
    data class Error(val message: String) : SaveResult()
}

// ── ViewModel ─────────────────────────────────────────────────────────────

@HiltViewModel
class DistressViewModel @Inject constructor(
    private val surveyEngine: SurveyEngine,
    private val photoAnalysisRepository: PhotoAnalysisRepository
) : ViewModel() {

    private val _saveResult = MutableLiveData<SaveResult>()
    val saveResult: LiveData<SaveResult> = _saveResult

    // ── Akses data engine untuk BottomSheet ───────────────────────────────

    fun getCurrentDistance(): Double = surveyEngine.getCurrentDistance()

    fun getCurrentLocation(): LocationData? = surveyEngine.getLastLocation()

    fun getRoadName(): String = surveyEngine.roadName.value

    // ── Simpan distress item ──────────────────────────────────────────────

    /**
     * Simpan data kerusakan ke survey engine.
     *
     * Setelah berhasil disimpan:
     * - Jika ada foto → otomatis didaftarkan ke antrian analisis AI (PENDING)
     * - Analisis AI sendiri tidak dijalankan di sini — dijalankan nanti
     *   dari SummaryFragment saat user tap "Analisis AI"
     */
    fun saveDistress(
        type: DistressType,
        severity: Severity,
        lengthOrArea: Double,
        photoPath: String,
        audioPath: String,
        notes: String
    ) {
        viewModelScope.launch {
            _saveResult.value = SaveResult.Loading

            if (lengthOrArea <= 0) {
                _saveResult.value = SaveResult.Error("Panjang/Luas harus lebih dari 0")
                return@launch
            }

            try {
                // Simpan ke engine → engine simpan ke DB via SurveyRepository
                surveyEngine.addDistressItem(
                    type         = type,
                    severity     = severity,
                    lengthOrArea = lengthOrArea,
                    photoPath    = photoPath,
                    audioPath    = audioPath,
                    notes        = notes.takeIf { it.isNotBlank() }
                )

                // Jika ada foto → daftarkan ke antrian analisis AI (non-blocking)
                if (photoPath.isNotBlank()) {
                    registerPhotoForAnalysis(photoPath)
                }

                _saveResult.value = SaveResult.Success

            } catch (e: Exception) {
                Timber.e(e, "Error saving distress")
                _saveResult.value = SaveResult.Error("Gagal menyimpan data: ${e.message}")
            }
        }
    }

    /**
     * Daftarkan foto ke antrian analisis AI.
     * Status awal = PENDING, analisis sebenarnya dijalankan dari SummaryFragment.
     *
     * Jika foto sudah pernah didaftarkan (misal DONE / FAILED), enqueuePhotosForSession
     * akan skip otomatis (tidak duplikat).
     */
    private suspend fun registerPhotoForAnalysis(photoPath: String) {
        val sessionId = surveyEngine.getCurrentSessionId() ?: return
        try {
            photoAnalysisRepository.enqueuePhotosForSession(
                sessionId  = sessionId,
                photoPaths = listOf(photoPath)
            )
            Timber.d("Foto didaftarkan ke antrian AI: $photoPath")
        } catch (e: Exception) {
            // Gagal daftarkan tidak boleh crash atau batalkan save distress
            Timber.w(e, "Gagal daftarkan foto ke antrian AI: $photoPath")
        }
    }
}