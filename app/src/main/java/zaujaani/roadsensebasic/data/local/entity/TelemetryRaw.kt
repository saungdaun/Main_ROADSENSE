package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "telemetry_raw")
data class TelemetryRaw(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val speed: Float,
    val vibrationZ: Float,
    val gpsAccuracy: Float,
    val altitude: Double,
    val cumulativeDistance: Double

)