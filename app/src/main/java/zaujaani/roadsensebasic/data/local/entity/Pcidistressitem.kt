package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * PCIDistressItem — satu entri kerusakan di segmen PCI.
 *
 * Berbeda dari DistressItem (SDI) karena:
 * - Pakai PCIDistressType (19 tipe ASTM) bukan DistressType (3 tipe SDI)
 * - Menyimpan hasil Deduct Value per item
 * - Density dihitung terhadap sampleAreaM2 dari SegmentPci
 */
@Entity(
    tableName = "pci_distress_items",
    foreignKeys = [
        ForeignKey(
            entity        = SegmentPci::class,
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
data class PCIDistressItem(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val segmentId: Long,
    val sessionId: Long,

    // ── Input surveyor ────────────────────────────────────────────────────
    val type: PCIDistressType,
    val severity: Severity,
    val quantity: Double,           // m², m, atau count sesuai unit tipe
    val notes: String = "",

    // ── Hasil kalkulasi (dihitung engine saat insert) ─────────────────────
    val density: Double = 0.0,      // quantity / sampleArea × 100
    val deductValue: Double = 0.0,  // dari kurva ASTM

    // ── Dokumentasi ───────────────────────────────────────────────────────
    val photoPath: String = "",
    val audioPath: String = "",
    val gpsLat: Double = 0.0,
    val gpsLng: Double = 0.0,
    val sta: String = "",

    val createdAt: Long = System.currentTimeMillis()
)