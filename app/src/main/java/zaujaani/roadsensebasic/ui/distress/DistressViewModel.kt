package zaujaani.roadsensebasic.ui.distress

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsensebasic.data.local.entity.*
import zaujaani.roadsensebasic.data.repository.PhotoAnalysisRepository
import zaujaani.roadsensebasic.domain.engine.SurveyEngine
import zaujaani.roadsensebasic.domain.model.LocationData
import javax.inject.Inject

sealed class SaveResult {
    object Loading : SaveResult()
    object Success : SaveResult()
    data class Error(val message: String) : SaveResult()
}

@HiltViewModel
class DistressViewModel @Inject constructor(
    private val surveyEngine: SurveyEngine,
    private val photoAnalysisRepository: PhotoAnalysisRepository
) : ViewModel() {

    private val _saveResult = MutableLiveData<SaveResult>()
    val saveResult: LiveData<SaveResult> = _saveResult

    // ── Akses data engine ───────────────────────────────────────────────
    fun getCurrentDistance(): Double = surveyEngine.getCurrentDistance()
    fun getCurrentLocation(): LocationData? = surveyEngine.getLastLocation()
    fun getRoadName(): String = surveyEngine.roadName.value
    fun getSurveyMode(): SurveyMode = surveyEngine.getCurrentMode()

    // ── Simpan distress item untuk SDI ──────────────────────────────────
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
                surveyEngine.addDistressItem(
                    type         = type,
                    severity     = severity,
                    lengthOrArea = lengthOrArea,
                    photoPath    = photoPath,
                    audioPath    = audioPath,
                    notes        = notes.takeIf { it.isNotBlank() }
                )

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

    // ── Simpan distress item untuk PCI (BARU) ───────────────────────────
    fun savePciDistress(
        type: PCIDistressType,
        severity: Severity,
        quantity: Double,
        photoPath: String,
        audioPath: String,
        notes: String
    ) {
        viewModelScope.launch {
            _saveResult.value = SaveResult.Loading

            if (quantity <= 0) {
                _saveResult.value = SaveResult.Error("Quantity harus lebih dari 0")
                return@launch
            }

            try {
                surveyEngine.addPciDistressItem(
                    type       = type,
                    severity   = severity,
                    quantity   = quantity,
                    photoPath  = photoPath,
                    audioPath  = audioPath,
                    notes      = notes
                )

                if (photoPath.isNotBlank()) {
                    registerPhotoForAnalysis(photoPath)
                }

                _saveResult.value = SaveResult.Success

            } catch (e: Exception) {
                Timber.e(e, "Error saving PCI distress")
                _saveResult.value = SaveResult.Error("Gagal menyimpan data: ${e.message}")
            }
        }
    }

    private suspend fun registerPhotoForAnalysis(photoPath: String) {
        val sessionId = surveyEngine.getCurrentSessionId() ?: return
        try {
            photoAnalysisRepository.enqueuePhotosForSession(
                sessionId  = sessionId,
                photoPaths = listOf(photoPath)
            )
            Timber.d("Foto didaftarkan ke antrian AI: $photoPath")
        } catch (e: Exception) {
            Timber.w(e, "Gagal daftarkan foto ke antrian AI: $photoPath")
        }
    }
}