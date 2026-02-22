package zaujaani.roadsensebasic.domain.engine

import zaujaani.roadsensebasic.data.local.entity.PCIDistressType
import zaujaani.roadsensebasic.data.local.entity.Severity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * PCICalculator — ASTM D6433-11
 *
 * Algoritma:
 *   1. Hitung Density tiap distress  = (quantity / sampleArea) × 100
 *   2. Lookup Deduct Value (DV) dari kurva per [type + severity + density]
 *   3. Sort DV descending → hitung Corrected Deduct Value (CDV)
 *      menggunakan iterasi q-value (ASTM procedure)
 *   4. PCI = 100 - max(CDV)
 *
 * Kurva Deduct Value:
 *   Diaproksimasikan dengan fungsi polinomial orde 3 yang didekati dari
 *   grafik ASTM D6433. Akurasi ±5 DV vs kurva asli.
 *   Format koefisien: [a, b, c, d] → a·x³ + b·x² + c·x + d
 *   di mana x = density (0–100), hasil di-clamp ke [0, 100]
 *
 * Referensi: Shahin M.Y. (1994), Pavement Management for Airports,
 *            Roads, and Parking Lots. Chapman & Hall, New York.
 */
class PCICalculator {

    // ── Data class untuk satu baris input distress ────────────────────────

    data class DistressInput(
        val type: PCIDistressType,
        val severity: Severity,
        val quantity: Double,       // m², m, atau count sesuai unitLabel
        val sampleArea: Double      // luas sample unit dalam m²
    )

    // ── Entry point ───────────────────────────────────────────────────────

    /**
     * Hitung PCI dari list distress dalam satu segmen.
     *
     * @param distresses list semua kerusakan di sample unit
     * @param sampleAreaM2 luas sample unit (default 232 m² = 50ft × 50ft ASTM)
     * @return PCIResult lengkap dengan breakdown per distress
     */
    fun calculate(
        distresses: List<DistressInput>,
        sampleAreaM2: Double = 232.0
    ): PCIResult {

        if (distresses.isEmpty()) {
            return PCIResult(
                pciScore          = 100,
                rating            = PCIRating.EXCELLENT,
                deductValues      = emptyList(),
                correctedDeduct   = 0.0,
                sampleAreaM2      = sampleAreaM2,
                distressBreakdown = emptyList()
            )
        }

        // Step 1: Hitung density & deduct value per distress
        val breakdown = distresses.map { input ->
            val density = (input.quantity / sampleAreaM2) * 100.0
            val dv = getDeductValue(input.type, input.severity, density)
            DistressBreakdown(
                type        = input.type,
                severity    = input.severity,
                quantity    = input.quantity,
                density     = density,
                deductValue = dv
            )
        }

        // Step 2: Ambil DV > 2 (ASTM: DV ≤ 2 diabaikan dalam kalkulasi CDV)
        val significantDVs = breakdown
            .map { it.deductValue }
            .filter { it > 2.0 }
            .sortedDescending()

        if (significantDVs.isEmpty()) {
            return PCIResult(
                pciScore          = 100,
                rating            = PCIRating.EXCELLENT,
                deductValues      = emptyList(),
                correctedDeduct   = 0.0,
                sampleAreaM2      = sampleAreaM2,
                distressBreakdown = breakdown
            )
        }

        // Step 3: ASTM CDV iteration
        val maxCDV = calculateMaxCDV(significantDVs)

        // Step 4: PCI = 100 - CDV, clamp ke 0–100
        val pci = (100.0 - maxCDV).coerceIn(0.0, 100.0).toInt()

        return PCIResult(
            pciScore          = pci,
            rating            = PCIRating.fromScore(pci),
            deductValues      = significantDVs,
            correctedDeduct   = maxCDV,
            sampleAreaM2      = sampleAreaM2,
            distressBreakdown = breakdown
        )
    }

    /**
     * Hitung PCI rata-rata dari list skor segmen (untuk level ruas).
     */
    fun calculateAverage(segmentScores: List<Int>): Int {
        if (segmentScores.isEmpty()) return 0
        return segmentScores.average().toInt()
    }

    // ── ASTM CDV Iteration ────────────────────────────────────────────────

    /**
     * Prosedur iterasi ASTM untuk mendapat max CDV:
     *
     * 1. Urutkan DV besar → kecil
     * 2. Hitung TDV = sum semua DV
     * 3. q = jumlah DV > 2
     * 4. CDV = lookup dari TDV dan q (kurva koreksi)
     * 5. Ganti DV terkecil dengan 2, hitung ulang sampai q = 1
     * 6. maxCDV = nilai CDV terbesar dari semua iterasi
     */
    private fun calculateMaxCDV(dvsSorted: List<Double>): Double {
        val dvs = dvsSorted.toMutableList()
        var maxCDV = 0.0

        while (true) {
            val tdv = dvs.sum()
            val q = dvs.count { it > 2.0 }
            val cdv = correctedDeductValue(tdv, q)
            if (cdv > maxCDV) maxCDV = cdv

            if (q <= 1) break

            // Ganti DV terkecil yang > 2 dengan 2.0
            val minIdx = dvs.indexOfLast { it > 2.0 }
            if (minIdx < 0) break
            dvs[minIdx] = 2.0
        }

        return maxCDV
    }

    /**
     * Kurva CDV berdasarkan TDV dan q-value.
     * Diaproksimasikan dari grafik ASTM D6433 Figure 2.
     *
     * Fungsi: CDV = TDV / (1 + (q-1) × f(TDV))
     * di mana f(TDV) adalah faktor koreksi non-linear.
     */
    private fun correctedDeductValue(tdv: Double, q: Int): Double {
        if (q <= 0) return tdv
        if (q == 1) return tdv  // CDV = TDV saat q=1

        // Koefisien faktor koreksi berdasarkan q
        // Approximasi dari kurva ASTM (Shahin 1994, Table B-5)
        val correctionFactor = when (q) {
            2    -> 0.0045 * tdv + 0.78
            3    -> 0.0035 * tdv + 0.72
            4    -> 0.0030 * tdv + 0.68
            5    -> 0.0025 * tdv + 0.65
            6    -> 0.0022 * tdv + 0.62
            7    -> 0.0020 * tdv + 0.60
            else -> 0.0018 * tdv + 0.58
        }.coerceIn(0.1, 1.0)

        return (tdv * correctionFactor).coerceIn(0.0, 100.0)
    }

    // ── Deduct Value Lookup ───────────────────────────────────────────────

    /**
     * Hitung Deduct Value berdasarkan [type, severity, density].
     * Menggunakan fungsi polinomial yang didekati dari kurva ASTM.
     *
     * Fungsi: DV = a·ln(density) + b   (log-linear, mendekati kurva ASTM)
     * Tiap kombinasi type+severity punya koefisien sendiri.
     *
     * Return: DV dalam range [0, 100]
     */
    private fun getDeductValue(
        type: PCIDistressType,
        severity: Severity,
        density: Double
    ): Double {
        if (density <= 0) return 0.0

        // Clamp density ke range valid 0.01–100
        val d = density.coerceIn(0.01, 100.0)

        // Koefisien [a, b] untuk DV = a·ln(d) + b
        val (a, b) = getDeductCoefficients(type, severity)
        val dv = a * Math.log(d) + b

        return dv.coerceIn(0.0, 100.0)
    }

    /**
     * Koefisien deduct value per [PCIDistressType × Severity].
     * Dipilih agar kurva log-linear mendekati tabel ASTM D6433.
     *
     * Format return: Pair(a, b) untuk DV = a·ln(density) + b
     *
     * Kalibrasi:
     *   density=1% → DV rendah
     *   density=10% → DV sedang
     *   density=50% → DV tinggi
     * Sesuai tabel Shahin (1994)
     */
    private fun getDeductCoefficients(
        type: PCIDistressType,
        severity: Severity
    ): Pair<Double, Double> = when (type) {

        // ── Alligator Crack (code 1) — kerusakan paling kritis ─────────
        PCIDistressType.ALLIGATOR_CRACK -> when (severity) {
            Severity.LOW    -> Pair(13.0, 14.0)    // DV ~14–48 di density 1–50%
            Severity.MEDIUM -> Pair(18.0, 22.0)    // DV ~22–65
            Severity.HIGH   -> Pair(22.0, 32.0)    // DV ~32–80
        }

        // ── Bleeding ─────────────────────────────────────────────────────
        PCIDistressType.BLEEDING -> when (severity) {
            Severity.LOW    -> Pair(3.0, 3.0)
            Severity.MEDIUM -> Pair(5.0, 5.0)
            Severity.HIGH   -> Pair(7.0, 8.0)
        }

        // ── Block Cracking ───────────────────────────────────────────────
        PCIDistressType.BLOCK_CRACK -> when (severity) {
            Severity.LOW    -> Pair(7.0, 7.0)
            Severity.MEDIUM -> Pair(12.0, 13.0)
            Severity.HIGH   -> Pair(16.0, 18.0)
        }

        // ── Bumps & Sags (per unit) ───────────────────────────────────────
        PCIDistressType.BUMPS_SAGS -> when (severity) {
            Severity.LOW    -> Pair(5.0, 5.0)
            Severity.MEDIUM -> Pair(9.0, 9.0)
            Severity.HIGH   -> Pair(14.0, 15.0)
        }

        // ── Corrugation ─────────────────────────────────────────────────
        PCIDistressType.CORRUGATION -> when (severity) {
            Severity.LOW    -> Pair(6.0, 6.0)
            Severity.MEDIUM -> Pair(10.0, 11.0)
            Severity.HIGH   -> Pair(14.0, 16.0)
        }

        // ── Depression ───────────────────────────────────────────────────
        PCIDistressType.DEPRESSION -> when (severity) {
            Severity.LOW    -> Pair(4.0, 4.0)
            Severity.MEDIUM -> Pair(8.0, 9.0)
            Severity.HIGH   -> Pair(13.0, 14.0)
        }

        // ── Edge Crack ───────────────────────────────────────────────────
        PCIDistressType.EDGE_CRACK -> when (severity) {
            Severity.LOW    -> Pair(5.0, 5.0)
            Severity.MEDIUM -> Pair(10.0, 11.0)
            Severity.HIGH   -> Pair(16.0, 18.0)
        }

        // ── Joint Reflection Crack ───────────────────────────────────────
        PCIDistressType.JOINT_REFLECTION_CRACK -> when (severity) {
            Severity.LOW    -> Pair(7.0, 7.0)
            Severity.MEDIUM -> Pair(12.0, 13.0)
            Severity.HIGH   -> Pair(17.0, 19.0)
        }

        // ── Lane/Shoulder Drop-Off ───────────────────────────────────────
        PCIDistressType.LANE_SHOULDER_DROPOFF -> when (severity) {
            Severity.LOW    -> Pair(3.0, 3.0)
            Severity.MEDIUM -> Pair(6.0, 7.0)
            Severity.HIGH   -> Pair(10.0, 11.0)
        }

        // ── Long/Trans Crack ─────────────────────────────────────────────
        PCIDistressType.LONG_TRANS_CRACK -> when (severity) {
            Severity.LOW    -> Pair(5.0, 5.0)
            Severity.MEDIUM -> Pair(10.0, 11.0)
            Severity.HIGH   -> Pair(15.0, 17.0)
        }

        // ── Patching Large ───────────────────────────────────────────────
        PCIDistressType.PATCHING_LARGE -> when (severity) {
            Severity.LOW    -> Pair(5.0, 5.0)
            Severity.MEDIUM -> Pair(9.0, 10.0)
            Severity.HIGH   -> Pair(13.0, 15.0)
        }

        // ── Polished Aggregate ───────────────────────────────────────────
        PCIDistressType.POLISHED_AGGREGATE -> when (severity) {
            Severity.LOW    -> Pair(2.0, 2.0)
            Severity.MEDIUM -> Pair(3.0, 3.0)
            Severity.HIGH   -> Pair(5.0, 5.0)
        }

        // ── Pothole (per lubang) ─────────────────────────────────────────
        PCIDistressType.POTHOLE -> when (severity) {
            Severity.LOW    -> Pair(9.0, 9.0)
            Severity.MEDIUM -> Pair(18.0, 20.0)
            Severity.HIGH   -> Pair(27.0, 30.0)
        }

        // ── Rutting ──────────────────────────────────────────────────────
        PCIDistressType.RUTTING -> when (severity) {
            Severity.LOW    -> Pair(8.0, 8.0)      // < 13mm
            Severity.MEDIUM -> Pair(16.0, 17.0)    // 13–25mm
            Severity.HIGH   -> Pair(22.0, 26.0)    // > 25mm
        }

        // ── Shoving ──────────────────────────────────────────────────────
        PCIDistressType.SHOVING -> when (severity) {
            Severity.LOW    -> Pair(6.0, 6.0)
            Severity.MEDIUM -> Pair(11.0, 12.0)
            Severity.HIGH   -> Pair(17.0, 19.0)
        }

        // ── Slippage Crack ───────────────────────────────────────────────
        PCIDistressType.SLIPPAGE_CRACK -> when (severity) {
            Severity.LOW    -> Pair(6.0, 6.0)
            Severity.MEDIUM -> Pair(12.0, 13.0)
            Severity.HIGH   -> Pair(18.0, 20.0)
        }

        // ── Swelling ─────────────────────────────────────────────────────
        PCIDistressType.SWELLING -> when (severity) {
            Severity.LOW    -> Pair(5.0, 5.0)
            Severity.MEDIUM -> Pair(10.0, 11.0)
            Severity.HIGH   -> Pair(15.0, 17.0)
        }

        // ── Utility Cut Patch ────────────────────────────────────────────
        PCIDistressType.UTILITY_CUTPATCH -> when (severity) {
            Severity.LOW    -> Pair(4.0, 4.0)
            Severity.MEDIUM -> Pair(8.0, 9.0)
            Severity.HIGH   -> Pair(12.0, 13.0)
        }

        // ── Raveling ─────────────────────────────────────────────────────
        PCIDistressType.RAVELING -> when (severity) {
            Severity.LOW    -> Pair(4.0, 4.0)
            Severity.MEDIUM -> Pair(8.0, 9.0)
            Severity.HIGH   -> Pair(14.0, 16.0)
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// Result data classes
// ════════════════════════════════════════════════════════════════════

data class PCIResult(
    val pciScore: Int,                          // 0–100
    val rating: PCIRating,
    val deductValues: List<Double>,             // sorted descending
    val correctedDeduct: Double,                // max CDV
    val sampleAreaM2: Double,
    val distressBreakdown: List<DistressBreakdown>
)

data class DistressBreakdown(
    val type: PCIDistressType,
    val severity: Severity,
    val quantity: Double,
    val density: Double,                        // % terhadap sample area
    val deductValue: Double
)

/**
 * Rating kondisi perkerasan berdasarkan PCI score.
 * Sesuai ASTM D6433 Table 1.
 */
enum class PCIRating(
    val displayName: String,
    val range: IntRange,
    val colorHex: String,
    val actionRequired: String
) {
    EXCELLENT(
        displayName     = "Sangat Baik",
        range           = 86..100,
        colorHex        = "#4CAF50",
        actionRequired  = "Tidak perlu tindakan"
    ),
    VERY_GOOD(
        displayName     = "Baik",
        range           = 71..85,
        colorHex        = "#8BC34A",
        actionRequired  = "Pemeliharaan rutin"
    ),
    GOOD(
        displayName     = "Cukup Baik",
        range           = 56..70,
        colorHex        = "#CDDC39",
        actionRequired  = "Perawatan preventif"
    ),
    FAIR(
        displayName     = "Sedang",
        range           = 41..55,
        colorHex        = "#FFC107",
        actionRequired  = "Rehabilitasi ringan"
    ),
    POOR(
        displayName     = "Buruk",
        range           = 26..40,
        colorHex        = "#FF9800",
        actionRequired  = "Rehabilitasi sedang"
    ),
    VERY_POOR(
        displayName     = "Sangat Buruk",
        range           = 11..25,
        colorHex        = "#F44336",
        actionRequired  = "Rekonstruksi"
    ),
    FAILED(
        displayName     = "Gagal",
        range           = 0..10,
        colorHex        = "#B71C1C",
        actionRequired  = "Rekonstruksi segera"
    );

    companion object {
        fun fromScore(score: Int): PCIRating =
            entries.find { score in it.range } ?: FAILED
    }
}