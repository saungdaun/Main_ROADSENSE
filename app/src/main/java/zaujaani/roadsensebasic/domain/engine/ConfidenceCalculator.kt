package zaujaani.roadsensebasic.domain.engine

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class yang mewakili faktor-faktor yang mempengaruhi confidence score.
 * Memudahkan penambahan parameter di masa depan tanpa mengubah signature fungsi.
 */
data class ConfidenceFactors(
    val gpsAvailability: Boolean,
    val gpsAccuracy: Float,
    val vibrationConsistency: Double,
    val speed: Float
)

@Singleton
class ConfidenceCalculator @Inject constructor() {

    companion object {
        // Bobot untuk masing-masing komponen (harus total 1.0)
        private const val WEIGHT_GPS_AVAILABILITY = 0.4
        private const val WEIGHT_GPS_ACCURACY = 0.3
        private const val WEIGHT_VIBRATION_CONSISTENCY = 0.2
        private const val WEIGHT_SPEED = 0.1

        // Skor maksimum per komponen (setelah dikalikan bobot, total maks 100)
        private const val MAX_GPS_AVAILABILITY_SCORE = 40
        private const val MAX_GPS_ACCURACY_SCORE = 30
        private const val MAX_VIBRATION_CONSISTENCY_SCORE = 20
        private const val MAX_SPEED_SCORE = 10
    }

    /**
     * Menghitung confidence score (0-100) berdasarkan faktor-faktor yang diberikan.
     * Menggunakan bobot yang dapat disesuaikan untuk memudahkan tuning.
     */
    fun calculateConfidence(factors: ConfidenceFactors): Int {
        val (gpsAvailability, gpsAccuracy, vibrationConsistency, speed) = factors

        // Skor komponen (tanpa bobot)
        val gpsAvailabilityScore = if (gpsAvailability) MAX_GPS_AVAILABILITY_SCORE else 0

        val gpsAccuracyScore = when {
            gpsAccuracy < 5 -> MAX_GPS_ACCURACY_SCORE
            gpsAccuracy < 10 -> 20
            gpsAccuracy < 20 -> 10
            else -> 0
        }

        val vibrationConsistencyScore = when {
            vibrationConsistency > 0.8 -> MAX_VIBRATION_CONSISTENCY_SCORE
            vibrationConsistency > 0.5 -> 15
            vibrationConsistency > 0.3 -> 10
            else -> 5
        }

        val speedScore = if (speed > 1.4) MAX_SPEED_SCORE else 0

        // Terapkan bobot
        val weightedScore = (
                gpsAvailabilityScore * WEIGHT_GPS_AVAILABILITY +
                        gpsAccuracyScore * WEIGHT_GPS_ACCURACY +
                        vibrationConsistencyScore * WEIGHT_VIBRATION_CONSISTENCY +
                        speedScore * WEIGHT_SPEED
                ).toInt()

        val finalScore = weightedScore.coerceIn(0, 100)

        // Logging untuk debugging
        Timber.d("Confidence score: $finalScore (GPS Avail=$gpsAvailability, Acc=$gpsAccuracy, Vib=$vibrationConsistency, Speed=$speed) -> raw scores: avail=$gpsAvailabilityScore, acc=$gpsAccuracyScore, vib=$vibrationConsistencyScore, speed=$speedScore")

        return finalScore
    }

    // Overload untuk kompatibilitas dengan kode lama (jika ada)
    fun calculateConfidence(
        gpsAvailability: Boolean,
        gpsAccuracy: Float,
        vibrationConsistency: Double,
        speed: Float
    ): Int {
        return calculateConfidence(ConfidenceFactors(gpsAvailability, gpsAccuracy, vibrationConsistency, speed))
    }
}