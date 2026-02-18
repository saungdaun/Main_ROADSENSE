package zaujaani.roadsensebasic.domain.engine

import zaujaani.roadsensebasic.util.Constants
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Extension function untuk menghitung RMS (Root Mean Square) dari List<Float>.
 */
fun List<Float>.rms(): Double {
    if (isEmpty()) return 0.0
    val sumSq = sumOf { (it * it).toDouble() }
    return sqrt(sumSq / size)
}

@Singleton
class VibrationAnalyzer @Inject constructor() {

    /**
     * Analisis vibrasi dari satu sumbu (misalnya Z).
     * @param values Daftar nilai akselerasi (dalam g) pada satu sumbu.
     * @return Nilai RMS (Root Mean Square) sebagai indikator getaran.
     */
    fun analyzeVibration(values: List<Float>): Double {
        return values.rms()
    }

    /**
     * Analisis vibrasi 3D menggunakan magnitudo vektor (√(x² + y² + z²)).
     * @param x Daftar nilai akselerasi sumbu X.
     * @param y Daftar nilai akselerasi sumbu Y.
     * @param z Daftar nilai akselerasi sumbu Z.
     * @return Nilai RMS dari magnitudo vektor.
     */
    fun analyzeVibration3D(x: List<Float>, y: List<Float>, z: List<Float>): Double {
        // Pastikan semua list memiliki ukuran yang sama (gunakan ukuran terkecil)
        val size = minOf(x.size, y.size, z.size)
        if (size == 0) return 0.0

        val magnitudes = List(size) { i ->
            sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
        }
        return magnitudes.rms()
    }

    /**
     * Mengklasifikasikan kondisi jalan berdasarkan nilai RMS.
     * @param rms Nilai RMS hasil analisis.
     * @return String kondisi jalan (BAIK, SEDANG, RUSAK_RINGAN, RUSAK_BERAT).
     */
    fun classifyCondition(rms: Double): String = when {
        rms < Constants.DEFAULT_THRESHOLD_BAIK -> Constants.CONDITION_BAIK
        rms < Constants.DEFAULT_THRESHOLD_SEDANG -> Constants.CONDITION_SEDANG
        rms < Constants.DEFAULT_THRESHOLD_RUSAK_RINGAN -> Constants.CONDITION_RUSAK_RINGAN
        else -> Constants.CONDITION_RUSAK_BERAT
    }

    /**
     * Menghitung skor konsistensi vibrasi (0–1).
     * Semakin kecil standar deviasi, semakin tinggi konsistensi.
     * @param values Daftar nilai akselerasi.
     * @return Skor konsistensi (1 / (1 + stdDev)).
     */
    fun calculateConsistency(values: List<Float>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        return 1.0 / (1.0 + stdDev)
    }
}