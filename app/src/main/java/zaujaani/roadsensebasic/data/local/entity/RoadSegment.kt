package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entitas segmen ruas jalan yang disurvey.
 * Menyimpan data GPS awal/akhir untuk marker profesional di peta.
 */
@Entity(tableName = "road_segments")
data class RoadSegment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val startDistance: Double,
    val endDistance: Double,
    val avgVibration: Double,
    val conditionAuto: String,
    val confidence: Int,
    val name: String = "",
    val audioPath: String = "",
    val surfaceType: String = "",
    val notes: String = "",
    val photoPath: String = "",
    // GPS koordinat untuk marker peta
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
)