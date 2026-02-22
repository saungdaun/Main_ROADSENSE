package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "road_segments",
    foreignKeys = [
        ForeignKey(
            entity = SurveySession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class RoadSegment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val name: String,
    val startDistance: Double,
    val endDistance: Double,
    val avgVibration: Double,
    val conditionAuto: String,           // hasil deteksi otomatis
    val surfaceType: String,             // permukaan jalan
    val confidence: Int = 0,             // confidence score 0-100
    val sdiScore: Int = 0,               // untuk mode SDI
    val notes: String = "",
    val photoPath: String = "",
    val audioPath: String = "",
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
)