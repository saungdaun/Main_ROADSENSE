package zaujaani.roadsensebasic.domain.engine

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class ConfidenceFactors(
    val gpsAvailability: Boolean,
    val gpsAccuracy: Float,        // meter
    val vibrationConsistency: Double, // 0.0 - 1.0
    val speed: Float               // m/s
)

enum class ConfidenceLevel(val label: String) {
    HIGH("TINGGI"),
    MEDIUM("SEDANG"),
    LOW("RENDAH");
}

data class ConfidenceResult(
    val score: Int,                // 0-100
    val level: ConfidenceLevel,
    val messages: List<String>     // pesan untuk surveyor
)

/**
 * ConfidenceCalculator — menghitung kepercayaan data segmen.
 *
 * FIX: Formula sebelumnya salah — skor raw sudah pre-weighted (40 dari max 40)
 * lalu dikali weight lagi → max score hanya 30 dari 100.
 * Sekarang: skor raw 0-100 per komponen → dikali weight → total 0-100 benar.
 */
@Singleton
class ConfidenceCalculator @Inject constructor() {

    companion object {
        // Bobot total harus 1.0
        private const val WEIGHT_GPS_AVAILABILITY = 0.35
        private const val WEIGHT_GPS_ACCURACY     = 0.30
        private const val WEIGHT_VIBRATION        = 0.20
        private const val WEIGHT_SPEED            = 0.15
    }

    /**
     * Hitung confidence score (0-100) dengan breakdown pesan untuk surveyor.
     */
    fun calculateWithMessages(factors: ConfidenceFactors): ConfidenceResult {
        val messages = mutableListOf<String>()

        // ── GPS Availability: 0 atau 100 ──────────────────────────────────
        val gpsAvailScore = if (factors.gpsAvailability) 100 else 0
        if (!factors.gpsAvailability) {
            messages.add("⚠️  GPS tidak tersedia — posisi diinterpolasi")
        }

        // ── GPS Accuracy: 0-100 berdasarkan akurasi (m) ───────────────────
        val gpsAccScore = when {
            factors.gpsAccuracy <= 5f  -> 100
            factors.gpsAccuracy <= 10f -> 80
            factors.gpsAccuracy <= 15f -> 60
            factors.gpsAccuracy <= 20f -> 40
            factors.gpsAccuracy <= 30f -> 20
            else                        -> 0
        }
        if (factors.gpsAccuracy > 20f) {
            messages.add("⚠️  Akurasi GPS buruk (${factors.gpsAccuracy.toInt()}m) — tunggu sinyal membaik")
        } else if (factors.gpsAccuracy > 10f) {
            messages.add("ℹ️  Akurasi GPS sedang (${factors.gpsAccuracy.toInt()}m)")
        }

        // ── Vibration Consistency: 0-100 ──────────────────────────────────
        val vibScore = when {
            factors.vibrationConsistency > 0.8 -> 100
            factors.vibrationConsistency > 0.6 -> 80
            factors.vibrationConsistency > 0.4 -> 60
            factors.vibrationConsistency > 0.2 -> 40
            else                                -> 20
        }
        if (factors.vibrationConsistency < 0.4) {
            messages.add("⚠️  Getaran tidak konsisten — periksa sensor atau permukaan tidak seragam")
        }

        // ── Speed: 0 atau 100 berdasarkan apakah kendaraan bergerak ────────
        val speedKmh = factors.speed * 3.6f
        val speedScore = when {
            speedKmh >= 10f && speedKmh <= 70f -> 100
            speedKmh > 5f                       -> 60
            else                                -> 0
        }
        if (speedKmh < 5f) {
            messages.add("⚠️  Kendaraan terlalu lambat/berhenti (<5 km/h)")
        } else if (speedKmh > 70f) {
            messages.add("⚠️  Kecepatan terlalu tinggi (${speedKmh.toInt()} km/h) — data kurang akurat")
            messages.add("   Kecepatan ideal survey: 30-50 km/h")
        }

        // ── Weighted total ─────────────────────────────────────────────────
        val score = (
                gpsAvailScore  * WEIGHT_GPS_AVAILABILITY +
                        gpsAccScore    * WEIGHT_GPS_ACCURACY     +
                        vibScore       * WEIGHT_VIBRATION        +
                        speedScore     * WEIGHT_SPEED
                ).toInt().coerceIn(0, 100)

        val level = when {
            score >= 70 -> ConfidenceLevel.HIGH
            score >= 45 -> ConfidenceLevel.MEDIUM
            else        -> ConfidenceLevel.LOW
        }

        if (messages.isEmpty()) {
            messages.add("✅ Data berkualitas baik")
        }

        Timber.d(
            "Confidence: score=$score level=$level | " +
                    "gpsAvail=$gpsAvailScore gpsAcc=$gpsAccScore vib=$vibScore speed=$speedScore"
        )

        return ConfidenceResult(score, level, messages)
    }

    fun calculate(factors: ConfidenceFactors): Int = calculateWithMessages(factors).score

    // Backward-compat overload
    fun calculateConfidence(
        gpsAvailability: Boolean,
        gpsAccuracy: Float,
        vibrationConsistency: Double,
        speed: Float
    ): Int = calculate(ConfidenceFactors(gpsAvailability, gpsAccuracy, vibrationConsistency, speed))
}