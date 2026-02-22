package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SegmentPci — satu segmen PCI (sample unit ASTM).
 *
 * ASTM D6433: sample unit = 230±93 m² (sekitar 50m × 4.6m lebar lajur).
 * RoadSense menggunakan 50m per segmen sebagai default,
 * disesuaikan dengan lebar jalan yang diinput surveyor.
 *
 * Berbeda dari SegmentSdi (100m), PCI standar ASTM pakai 50m
 * supaya sample area mendekati 232 m².
 */
@Entity(
    tableName = "segment_pci",
    foreignKeys = [
        ForeignKey(
            entity      = SurveySession::class,
            parentColumns = ["id"],
            childColumns  = ["sessionId"],
            onDelete    = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sessionId", "segmentIndex"], unique = true)
    ]
)
data class SegmentPci(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: Long,
    val segmentIndex: Int,          // 0-based: segmen ke-0 = STA 0+000–0+050

    val startSta: String,           // format "0+000"
    val endSta: String,             // format "0+050"

    val startLat: Double = 0.0,
    val startLng: Double = 0.0,

    // ── Dimensi sample unit ───────────────────────────────────────────────
    val segmentLengthM: Double = 50.0,      // panjang segmen (default 50m)
    val laneWidthM: Double = 3.7,           // lebar lajur (default 3.7m)
    val sampleAreaM2: Double = 185.0,       // length × width (auto-calc)

    // ── Hasil PCI ─────────────────────────────────────────────────────────
    val pciScore: Int = -1,                 // -1 = belum dihitung
    val pciRating: String = "",             // "EXCELLENT", "GOOD", dll
    val correctedDeductValue: Double = 0.0, // max CDV sebelum PCI dihitung

    // ── Metadata distress ─────────────────────────────────────────────────
    val distressCount: Int = 0,             // jumlah item kerusakan
    val dominantDistressType: String = "",  // jenis kerusakan dengan DV tertinggi
    val deductValuesJson: String = "",      // raw JSON list DV untuk audit

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)