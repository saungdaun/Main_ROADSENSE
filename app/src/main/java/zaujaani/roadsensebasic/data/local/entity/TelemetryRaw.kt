package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "telemetry_raw",
    foreignKeys = [
        ForeignKey(
            entity = SurveySession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("sessionId"),
        Index("timestamp"),
        Index(value = ["sessionId", "timestamp"], name = "idx_session_timestamp")
    ]
)
data class TelemetryRaw(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: Long,
    val timestamp: Instant,

    // GPS
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Float,          // m/s
    val gpsAccuracy: Float,    // meter

    // Sensor
    val vibrationX: Float,
    val vibrationY: Float,
    val vibrationZ: Float,

    // Derived
    val cumulativeDistance: Double,  // meter dari awal sesi
    val confidence: Int,              // 0-100, kualitas data titik ini

    // Context labels
    val condition: Condition,
    val surface: Surface
)