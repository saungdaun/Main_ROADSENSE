package zaujaani.roadsensebasic.domain.engine

import zaujaani.roadsensebasic.data.local.entity.DistressItem
import zaujaani.roadsensebasic.data.local.entity.DistressType
import zaujaani.roadsensebasic.data.local.entity.Severity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SDICalculator v2 — Improved Surface Distress Index Calculation
 *
 * Perubahan dari v1:
 * 1. Formula lebih representatif — menggunakan deduct value berbasis extent
 * 2. Tidak throw IllegalArgumentException — gunakan default weight
 * 3. Mendukung lebih banyak jenis kerusakan (lihat DistressType)
 * 4. Penalti lebih besar untuk kombinasi kerusakan parah
 * 5. Output 0-100 di mana 0=sempurna, 100=rusak total
 *
 * Referensi: PD T-05-2005-B (Bina Marga) — SDI Classification
 *
 * Kategori SDI:
 *   0–20:  Baik Sekali   → tidak perlu penanganan
 *   21–40: Baik          → pemeliharaan rutin
 *   41–60: Sedang        → pemeliharaan berkala
 *   61–80: Rusak Ringan  → peningkatan/rehabilitasi
 *   81-100: Rusak Berat  → rekonstruksi
 *
 * Deduct Value Matrix (Extent × Severity):
 *   Extent = lengthOrArea / segmentLength (0-1)
 *   Setiap tipe kerusakan memiliki multiplier berbeda
 */
@Singleton
class SDICalculator @Inject constructor() {

    /**
     * Deduct Value per kerusakan = baseWeight × severityFactor × extent
     * Kemudian total dikonversi ke skala SDI 0-100.
     */
    private fun getBaseWeight(type: DistressType): Double = when (type) {
        DistressType.POTHOLE    -> 30.0  // Lubang: pengaruh terbesar ke SDI
        DistressType.RUTTING    -> 25.0  // Alur: bahaya aquaplaning
        DistressType.CRACK      -> 15.0  // Retak: menurunkan struktur
        DistressType.SPALLING   -> 12.0  // Pengelupasan
        DistressType.RAVELING   -> 10.0  // Lepas agregat
        DistressType.DEPRESSION -> 20.0  // Ambles: bahaya dan bergenang
        else                    -> 8.0   // Tipe lain default
    }

    private fun getSeverityFactor(severity: Severity): Double = when (severity) {
        Severity.LOW    -> 1.0
        Severity.MEDIUM -> 2.0
        Severity.HIGH   -> 3.5  // Parah tidak linear (bukan hanya 3x)
    }

    /**
     * Hitung SDI untuk satu segmen.
     *
     * @param distressItems daftar kerusakan dalam segmen
     * @param segmentLength panjang segmen dalam meter (default 100m untuk SDI standar)
     * @return nilai SDI 0-100 (0=baik sekali, 100=rusak total)
     */
    fun calculateSegmentSDI(
        distressItems: List<DistressItem>,
        segmentLength: Double = 100.0
    ): Int {
        if (distressItems.isEmpty() || segmentLength <= 0) return 0

        var totalDeductValue = 0.0

        for (item in distressItems) {
            val baseWeight = getBaseWeight(item.type)
            val severityFactor = getSeverityFactor(item.severity)
            val extent = (item.lengthOrArea / segmentLength).coerceIn(0.0, 1.0)

            // Deduct value per item
            val deductValue = baseWeight * severityFactor * extent
            totalDeductValue += deductValue
        }

        // Penalti kombinasi: jika banyak tipe berbeda, tambah penalti
        val uniqueTypes = distressItems.map { it.type }.distinct().size
        if (uniqueTypes > 2) {
            totalDeductValue *= (1.0 + (uniqueTypes - 2) * 0.10)
        }

        // Konversi ke skala 0-100
        // Max theoretical deduct ≈ 180 (semua jenis, severity HIGH, extent 100%)
        // Normalize ke 0-100, cap di 100
        val maxExpected = 180.0
        val sdi = ((totalDeductValue / maxExpected) * 100).coerceIn(0.0, 100.0)

        return sdi.toInt()
    }

    /**
     * Rata-rata SDI seluruh segmen dalam satu sesi.
     * Weighted by equal segment lengths (100m each).
     */
    fun calculateAverageSDI(scores: List<Int>): Int {
        if (scores.isEmpty()) return 0
        return scores.average().toInt()
    }

    /**
     * Validasi: apakah data cukup untuk SDI yang representatif?
     * Minimum 1 distress item per 100m segment.
     */
    fun isDataSufficient(distressItems: List<DistressItem>): Boolean {
        return distressItems.isNotEmpty()
    }

    /**
     * Pesan panduan untuk surveyor berdasarkan SDI score.
     */
    fun getRecommendation(sdi: Int): String = when (sdi) {
        in 0..20  -> "Kondisi Baik Sekali — pemeliharaan rutin"
        in 21..40 -> "Kondisi Baik — pemeliharaan rutin"
        in 41..60 -> "Kondisi Sedang — pemeliharaan berkala"
        in 61..80 -> "Kondisi Rusak Ringan — perlu rehabilitasi"
        else      -> "Kondisi Rusak Berat — perlu rekonstruksi"
    }

    companion object {
        fun categorizeSDI(sdi: Int): String = when (sdi) {
            in 0..20  -> "Baik Sekali"
            in 21..40 -> "Baik"
            in 41..60 -> "Sedang"
            in 61..80 -> "Rusak Ringan"
            else      -> "Rusak Berat"
        }
    }
}