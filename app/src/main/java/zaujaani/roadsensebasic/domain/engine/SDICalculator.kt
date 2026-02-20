package zaujaani.roadsensebasic.domain.engine

import zaujaani.roadsensebasic.data.local.entity.DistressItem
import zaujaani.roadsensebasic.data.local.entity.DistressType
import zaujaani.roadsensebasic.data.local.entity.Severity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SDICalculator @Inject constructor() {

    private val weightMap = mapOf(
        DistressType.CRACK to 1,
        DistressType.SPALLING to 2,
        DistressType.POTHole to 3
    )

    private val severityMap = mapOf(
        Severity.LOW to 1,
        Severity.MEDIUM to 2,
        Severity.HIGH to 3
    )

    /**
     * Menghitung SDI untuk satu segmen.
     * @param distressItems daftar distress dalam segmen
     * @param segmentLength panjang segmen dalam meter (misal 100 untuk SDI)
     * @return nilai SDI 0-100
     */
    fun calculateSegmentSDI(distressItems: List<DistressItem>, segmentLength: Double = 100.0): Int {
        if (distressItems.isEmpty() || segmentLength <= 0) return 0

        var totalScore = 0.0

        for (item in distressItems) {
            val weight = weightMap[item.type] ?: throw IllegalArgumentException("Weight tidak ditemukan untuk ${item.type}")
            val severity = severityMap[item.severity] ?: throw IllegalArgumentException("Severity tidak ditemukan untuk ${item.severity}")

            val ratio = (item.lengthOrArea / segmentLength).coerceIn(0.0, 1.0)
            totalScore += weight * severity * ratio
        }

        val scaled = totalScore * 10
        return scaled.coerceIn(0.0, 100.0).toInt()
    }

    fun calculateAverageSDI(scores: List<Int>): Int {
        if (scores.isEmpty()) return 0
        return scores.average().toInt()
    }

    companion object {
        fun categorizeSDI(sdi: Int): String = when (sdi) {
            in 0..20 -> "Sangat Baik"
            in 21..40 -> "Baik"
            in 41..60 -> "Sedang"
            in 61..80 -> "Rusak Ringan"
            else -> "Rusak Berat"
        }
    }
}