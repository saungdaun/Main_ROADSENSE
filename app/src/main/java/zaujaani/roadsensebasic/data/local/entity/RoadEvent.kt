package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class EventType {
    CONDITION_CHANGE,
    SURFACE_CHANGE,
    PHOTO,
    VOICE
}

@Entity(tableName = "road_events")
data class RoadEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,               // System.currentTimeMillis()
    val latitude: Double,
    val longitude: Double,
    val distance: Float,                // jarak dari awal survei dalam meter
    val eventType: EventType,
    val value: String,                  // misal: "Baik", "Aspal", atau path file
    val notes: String? = null
)