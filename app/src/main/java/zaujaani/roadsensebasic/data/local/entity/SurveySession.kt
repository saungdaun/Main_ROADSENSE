package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "survey_sessions")
data class SurveySession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val deviceModel: String = android.os.Build.MODEL,
    val totalDistance: Double = 0.0,
    val avgConfidence: Int = 0
)