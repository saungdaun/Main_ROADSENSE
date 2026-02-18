package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entitas sesi survey jalan.
 * Menyimpan data awal dan akhir sesi beserta nama surveyornya.
 */
@Entity(tableName = "survey_sessions")
data class SurveySession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val roadName: String = "",
    val endTime: Long? = null,
    val deviceModel: String = android.os.Build.MODEL,
    val surveyorName: String = "",
    val totalDistance: Double = 0.0,
    val avgConfidence: Int = 0,

    // GPS batas awal dan akhir ruas
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0
)
