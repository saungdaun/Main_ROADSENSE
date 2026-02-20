package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "distress_items",
    foreignKeys = [
        ForeignKey(
            entity = SegmentSdi::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class DistressItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val segmentId: Long,
    val sessionId: Long,
    val type: DistressType,
    val severity: Severity,
    val lengthOrArea: Double,
    val photoPath: String = "",
    val audioPath: String = "",
    val gpsLat: Double,
    val gpsLng: Double,
    val sta: String,
    val createdAt: Long = System.currentTimeMillis()
)