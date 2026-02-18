package zaujaani.roadsensebasic.ui.segment

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsensebasic.data.local.entity.RoadSegment
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import javax.inject.Inject

@HiltViewModel
class SegmentViewModel @Inject constructor(
    private val surveyRepository: SurveyRepository
) : ViewModel() {

    private val _saveResult = MutableLiveData<Boolean>()
    val saveResult: LiveData<Boolean> = _saveResult

    private val _isSaving = MutableLiveData(false)
    val isSaving: LiveData<Boolean> = _isSaving

    fun saveSegment(
        sessionId: Long,
        startDistance: Float,
        endDistance: Float,
        avgVibration: Float,
        conditionAuto: String,
        confidence: Int,
        roadName: String,
        manualCondition: String,
        surfaceType: String,
        notes: String,
        photoPath: String,
        audioPath: String
    ) {
        _isSaving.value = true

        val finalCondition = manualCondition.ifBlank { conditionAuto }

        val segment = RoadSegment(
            sessionId = sessionId,
            startDistance = startDistance.toDouble(),
            endDistance = endDistance.toDouble(),
            avgVibration = avgVibration.toDouble(),
            conditionAuto = finalCondition,
            confidence = confidence,
            name = roadName,
            surfaceType = surfaceType,
            notes = notes,
            photoPath = photoPath,
            audioPath = audioPath,
            // Koordinat tidak dikirim dari args, biarkan default 0.0
            startLat = 0.0,
            startLng = 0.0,
            endLat = 0.0,
            endLng = 0.0,
            createdAt = System.currentTimeMillis()
        )

        viewModelScope.launch {
            try {
                surveyRepository.insertSegment(segment)
                Timber.i("Segment saved: $roadName")
                _saveResult.value = true
            } catch (e: Exception) {
                Timber.e(e, "Failed to save segment")
                _saveResult.value = false
            } finally {
                _isSaving.value = false
            }
        }
    }
}