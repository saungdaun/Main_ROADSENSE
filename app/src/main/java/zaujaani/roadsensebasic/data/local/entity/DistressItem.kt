package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * DistressItem — satu entri kerusakan untuk survei SDI (Bina Marga).
 *
 * FIX #6 KRITIS: Kolom `notes` hilang dari skema sebelumnya.
 *   - DistressViewModel.saveDistress() mengirim `notes` ke addDistressItem()
 *   - SurveyEngine.addDistressItem() membuat DistressItem
 *   - Tapi entity tidak punya field `notes` → catatan surveyor HILANG diam-diam!
 *   - Diperbaiki dengan menambahkan field `notes` + migration 9→10
 */
@Entity(
    tableName = "distress_items",
    foreignKeys = [
        ForeignKey(
            entity        = SegmentSdi::class,
            parentColumns = ["id"],
            childColumns  = ["segmentId"],
            onDelete      = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["segmentId"]),
        Index(value = ["sessionId"])
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
    // FIX #6: Tambah field notes yang sebelumnya hilang
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)