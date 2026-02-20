package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representasi satu sesi survey jalan.
 * Menyimpan metadata, informasi lokasi, dan hasil perhitungan.
 */
@Entity(tableName = "survey_sessions")
data class SurveySession(

    // ===============================
    // Primary Key
    // ===============================
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // ===============================
    // Basic Information
    // ===============================
    val roadName: String = "",
    val surveyorName: String = "",
    val mode: SurveyMode = SurveyMode.GENERAL,

    // ===============================
    // Time Information
    // ===============================
    val startTime: Long,
    val endTime: Long? = null,

    // ===============================
    // Device Information
    // ===============================
    val deviceModel: String = android.os.Build.MODEL,

    // ===============================
    // Distance & Accuracy
    // ===============================
    val totalDistance: Double = 0.0,
    val avgConfidence: Int = 0,

    // ===============================
    // SDI Result (Level Ruas)
    // ===============================
    val avgSdi: Int = 0,

    // ===============================
    // GPS Boundary (Ruas Awal-Akhir)
    // ===============================
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0
)