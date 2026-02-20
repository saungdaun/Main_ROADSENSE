package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "segment_sdi",
    foreignKeys = [
        ForeignKey(
            entity = SurveySession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SegmentSdi(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val segmentIndex: Int,
    val startSta: String,
    val endSta: String,
    val sdiScore: Int = 0,
    val distressCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)