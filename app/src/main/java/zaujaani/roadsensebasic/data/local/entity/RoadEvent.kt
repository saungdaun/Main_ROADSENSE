package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class EventType {
    CONDITION_CHANGE,
    SURFACE_CHANGE,
    PHOTO,
    VOICE,
    NOTE
}

@Entity(
    tableName = "road_events",
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
data class RoadEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val distance: Float,           // jarak kumulatif saat event
    val eventType: EventType,
    val value: String,             // path file atau nilai baru
    val notes: String? = null
)