package zaujaani.roadsensebasic.domain.engine

import zaujaani.roadsensebasic.data.local.entity.DistressItem
import zaujaani.roadsensebasic.data.local.entity.DistressType
import zaujaani.roadsensebasic.data.local.entity.Severity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SDICalculator v3 — Surface Distress Index
 *
 * Referensi: PD T-05-2005-B (Bina Marga) — SDI Classification Categories
 * Formula: Modified Weighted Deduct Value (custom, terinspirasi dari metode Bina Marga)
 *
 * CATATAN METODOLOGI:
 *   SDI standar Bina Marga (PD T-05-2005-B) menggunakan observasi visual lapangan
 *   dengan penghitungan: luas retak (%), jumlah lubang, kedalaman alur, dan kondisi tepi.
 *   RoadSense menggunakan pendekatan "Modified Weighted Deduct Value" yang dapat
 *   dikalibrasi dengan data lapangan aktual oleh surveyor. Kategori SDI mengikuti
 *   klasifikasi Bina Marga.
 *
 * Kategori SDI (PD T-05-2005-B):
 *   0–20:   Baik Sekali  → tidak perlu penanganan
 *   21–40:  Baik         → pemeliharaan rutin
 *   41–60:  Sedang       → pemeliharaan berkala
 *   61–80:  Rusak Ringan → peningkatan/rehabilitasi
 *   81–100: Rusak Berat  → rekonstruksi
 *
 * FIX v3 (Bug #5): maxExpected dinaikkan dari 180 menjadi 350.
 *   Kalkulasi worst case:
 *   - POTHOLE HIGH: 30 × 3.5 × 1.0 = 105
 *   - RUTTING HIGH: 25 × 3.5 × 1.0 = 87.5
 *   - DEPRESSION HIGH: 20 × 3.5 × 1.0 = 70
 *   - CRACK HIGH: 15 × 3.5 × 1.0 = 52.5
 *   Subtotal 4 tipe = 315, + penalty kombinasi ≈ 347. maxExpected=350 aman.
 *
 * Dengan maxExpected=180 (sebelumnya), jalan dengan 2 tipe berat sudah SDI=100,
 * menghilangkan resolusi untuk kondisi sedang-berat. Sekarang diperbaiki.
 */
@Singleton
class SDICalculator @Inject constructor() {

    private fun getBaseWeight(type: DistressType): Double = when (type) {
        DistressType.POTHOLE    -> 30.0  // Lubang: pengaruh terbesar ke SDI
        DistressType.RUTTING    -> 25.0  // Alur: bahaya aquaplaning
        DistressType.CRACK      -> 15.0  // Retak: menurunkan struktur
        DistressType.SPALLING   -> 12.0  // Pengelupasan
        DistressType.RAVELING   -> 10.0  // Lepas agregat
        DistressType.DEPRESSION -> 20.0  // Ambles: bahaya dan bergenang
        else                    ->  8.0  // Tipe lain default
    }

    private fun getSeverityFactor(severity: Severity): Double = when (severity) {
        Severity.LOW    -> 1.0
        Severity.MEDIUM -> 2.0
        Severity.HIGH   -> 3.5   // Non-linear (kerusakan parah tidak sekadar 3×)
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
            val baseWeight     = getBaseWeight(item.type)
            val severityFactor = getSeverityFactor(item.severity)
            val extent         = (item.lengthOrArea / segmentLength).coerceIn(0.0, 1.0)
            totalDeductValue  += baseWeight * severityFactor * extent
        }

        // Penalti kombinasi: jika ada >2 tipe kerusakan berbeda, tambah penalti 10% per tipe
        val uniqueTypes = distressItems.map { it.type }.distinct().size
        if (uniqueTypes > 2) {
            totalDeductValue *= (1.0 + (uniqueTypes - 2) * 0.10)
        }

        // FIX v3: maxExpected dinaikkan ke 350 (sebelumnya 180, terlalu kecil).
        // Worst case dengan 4 tipe kerusakan HIGH extent=1.0: ~347
        val maxExpected = 350.0
        val sdi = ((totalDeductValue / maxExpected) * 100).coerceIn(0.0, 100.0)

        return sdi.toInt()
    }

    /**
     * Rata-rata SDI seluruh segmen dalam satu sesi (weighted equal per 100m segment).
     */
    fun calculateAverageSDI(scores: List<Int>): Int {
        if (scores.isEmpty()) return 0
        return scores.average().toInt()
    }

    fun isDataSufficient(distressItems: List<DistressItem>): Boolean {
        return distressItems.isNotEmpty()
    }

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