package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "survey_sessions")
data class SurveySession(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val roadName: String = "",
    val surveyorName: String = "",

    val mode: SurveyMode = SurveyMode.GENERAL,

    val startTime: Long,
    val endTime: Long? = null,

    val deviceModel: String = android.os.Build.MODEL,

    val totalDistance: Double = 0.0,
    val avgConfidence: Int = 0,

    // ── Survey Results ──
    val avgSdi: Int = 0,
    val avgPci: Int = -1,

    // ── Location ──
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0,

    // ── Duration ──
    val durationMinutes: Long = 0,

)