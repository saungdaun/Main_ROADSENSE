package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * TelemetryRaw — one GPS+sensor sample per location fix (~1 Hz).
 *
 * Every row captures the full environmental context at that moment:
 * speed, altitude, vibration (X/Y/Z RMS), GPS accuracy, cumulative distance,
 * and the current condition/surface label set by the surveyor.
 *
 * This table enables post-processing for:
 * - GPX/CSV export
 * - AI surface recognition input
 * - IRI (International Roughness Index) estimation
 */
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

    // ── GPS ──
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Float,          // m/s (harus >= 0)
    val gpsAccuracy: Float,    // meter (1-sigma, harus >= 0)

    // ── Sensor (linear acceleration dalam satuan g, setelah gravity removal) ──
    val vibrationX: Float,     // RMS sumbu X (atau raw jika tidak di-smoothing)
    val vibrationY: Float,     // RMS sumbu Y
    val vibrationZ: Float,     // RMS sumbu Z (primer untuk getaran jalan)

    // ── Derived ──
    val cumulativeDistance: Double,  // meter dari awal sesi

    // ── Context labels (diisi oleh surveyor saat pengambilan data) ──
    val condition: Condition,
    val surface: Surface
)