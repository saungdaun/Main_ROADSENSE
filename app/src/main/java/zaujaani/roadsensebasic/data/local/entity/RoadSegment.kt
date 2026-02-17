package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val createdAt: Long = System.currentTimeMillis()
)