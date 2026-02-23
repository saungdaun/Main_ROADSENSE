package zaujaani.roadsensebasic.util.sensor

import android.location.Location
import kotlin.math.abs

class GpsConfidenceFilter(
    private val maxAccuracy: Float = 15f,
    private val maxSpeed: Float = 45f, // 45 m/s ≈ 162 km/h
    private val maxJumpDistance: Float = 50f // meter
) {

    private var lastLocation: Location? = null

    /**
     * Validasi lokasi berdasarkan:
     * - Akurasi
     * - Kecepatan tidak wajar
     * - Lonjakan jarak mendadak
     * - Timestamp terlalu lama
     */
    fun isValid(location: Location): Boolean {

        // 1️⃣ Accuracy check
        if (location.accuracy > maxAccuracy) {
            return false
        }

        // 2️⃣ Speed sanity check
        if (location.hasSpeed() && location.speed > maxSpeed) {
            return false
        }

        // 3️⃣ Timestamp check (tidak lebih dari 5 detik delay)
        val now = System.currentTimeMillis()
        if (abs(now - location.time) > 5000) {
            return false
        }

        // 4️⃣ Jump distance check
        lastLocation?.let { prev ->
            val distance = prev.distanceTo(location)
            if (distance > maxJumpDistance) {
                return false
            }
        }

        lastLocation = location
        return true
    }

    /**
     * Optional: skor confidence 0–100
     */
    fun getConfidenceScore(location: Location): Int {
        var score = 100

        // Penalti akurasi
        score -= (location.accuracy / maxAccuracy * 40).toInt()

        // Penalti speed aneh
        if (location.hasSpeed() && location.speed > maxSpeed) {
            score -= 30
        }

        // Penalti lonjakan
        lastLocation?.let { prev ->
            val distance = prev.distanceTo(location)
            if (distance > maxJumpDistance) {
                score -= 30
            }
        }

        return score.coerceIn(0, 100)
    }

    fun reset() {
        lastLocation = null
    }
}