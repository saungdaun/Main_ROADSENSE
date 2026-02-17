package zaujaani.roadsensebasic.domain.engine

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class VibrationAnalyzer @Inject constructor() {

    fun analyzeVibration(values: List<Float>): Double {
        if (values.isEmpty()) return 0.0
        val sumSq = values.sumOf { (it * it).toDouble() }
        return sqrt(sumSq / values.size)
    }

    fun classifyCondition(rms: Double): String = when {
        rms < 0.3 -> "Baik"
        rms < 0.6 -> "Sedang"
        rms < 1.0 -> "Rusak Ringan"
        else -> "Rusak Berat"
    }

    fun calculateConsistency(values: List<Float>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        // Consistency score antara 0-1, semakin kecil stdDev semakin tinggi konsistensi
        return 1.0 / (1.0 + stdDev)
    }
}