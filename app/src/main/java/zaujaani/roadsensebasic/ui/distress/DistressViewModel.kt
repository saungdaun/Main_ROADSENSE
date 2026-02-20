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
    private val surveyEngine: SurveyEngine
) : ViewModel() {

    private val _saveResult = MutableLiveData<SaveResult>()
    val saveResult: LiveData<SaveResult> = _saveResult

    fun getCurrentDistance(): Double = surveyEngine.getCurrentDistance()

    fun getCurrentLocation(): LocationData? = surveyEngine.getLastLocation()

    fun getRoadName(): String = surveyEngine.roadName.value

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
                    type = type,
                    severity = severity,
                    lengthOrArea = lengthOrArea,
                    photoPath = photoPath,
                    audioPath = audioPath,
                    notes = notes.takeIf { it.isNotBlank() }
                )
                _saveResult.value = SaveResult.Success
            } catch (e: Exception) {
                Timber.e(e, "Error saving distress")
                _saveResult.value = SaveResult.Error("Gagal menyimpan data: ${e.message}")
            }
        }
    }
}