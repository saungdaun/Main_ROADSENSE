package zaujaani.roadsensebasic.domain.engine

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfidenceCalculator @Inject constructor() {
    fun calculateConfidence(
        gpsAvailability: Boolean,
        gpsAccuracy: Float,
        vibrationConsistency: Double,
        speed: Float
    ): Int {
        var score = 0
        if (gpsAvailability) score += 40
        score += when {
            gpsAccuracy < 5 -> 30
            gpsAccuracy < 10 -> 20
            gpsAccuracy < 20 -> 10
            else -> 0
        }
        score += when {
            vibrationConsistency > 0.8 -> 20
            vibrationConsistency > 0.5 -> 15
            vibrationConsistency > 0.3 -> 10
            else -> 5
        }
        if (speed > 1.4) score += 10
        return score.coerceIn(0, 100)
    }
}