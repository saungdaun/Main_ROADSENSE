package zaujaani.roadsensebasic.data.local.entity

/**
 * PCIDistressType — 19 jenis kerusakan ASTM D6433
 *
 * Dipakai untuk survey PCI (Pavement Condition Index).
 * Berbeda dari DistressType (SDI) — PCI lebih detail dan mengikuti
 * standar internasional ASTM D6433-11.
 *
 * Setiap tipe menyimpan:
 * - displayName: nama Indonesia + kode ASTM
 * - unit: cara pengukuran (m², m, atau per lubang)
 * - isAreaBased / isLinear: flag untuk validasi input
 * - deductCoeff: koefisien polinomial untuk hitung Deduct Value
 *   dari Density (%). Format: [a, b, c, d] untuk ax³+bx²+cx+d
 *   Didekati dari kurva ASTM D6433 (akurasi ±5%)
 *
 * Referensi: ASTM D6433-11, Shahin (1994) PCI Manual
 */
enum class PCIDistressType(
    val displayName: String,
    val astmCode: Int,
    val unitLabel: String,
    val isAreaBased: Boolean,
    val isLinear: Boolean       // false = per unit/count
) {

    // ── Area-based (m²) ───────────────────────────────────────────────────

    ALLIGATOR_CRACK(
        displayName  = "Retak Buaya (Alligator)",
        astmCode     = 1,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),
    BLEEDING(
        displayName  = "Kegemukan (Bleeding)",
        astmCode     = 2,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),
    BLOCK_CRACK(
        displayName  = "Retak Blok (Block Cracking)",
        astmCode     = 3,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),
    DEPRESSION(
        displayName  = "Amblas (Depression)",
        astmCode     = 6,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),
    PATCHING_LARGE(
        displayName  = "Tambalan Besar (Patching)",
        astmCode     = 11,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),
    POLISHED_AGGREGATE(
        displayName  = "Agregat Licin (Polished Agg.)",
        astmCode     = 12,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),
    RAVELING(
        displayName  = "Pelepasan Butir (Raveling)",
        astmCode     = 19,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),
    SHOVING(
        displayName  = "Jembul (Shoving)",
        astmCode     = 15,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),
    SLIPPAGE_CRACK(
        displayName  = "Retak Selip (Slippage)",
        astmCode     = 16,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),
    SWELLING(
        displayName  = "Menggelembung (Swelling)",
        astmCode     = 18,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),

    // ── Linear (m) ────────────────────────────────────────────────────────

    EDGE_CRACK(
        displayName  = "Retak Tepi (Edge Crack)",
        astmCode     = 7,
        unitLabel    = "m",
        isAreaBased  = false,
        isLinear     = true
    ),
    JOINT_REFLECTION_CRACK(
        displayName  = "Retak Sambungan (Reflection)",
        astmCode     = 10,
        unitLabel    = "m",
        isAreaBased  = false,
        isLinear     = true
    ),
    LANE_SHOULDER_DROPOFF(
        displayName  = "Penurunan Bahu Jalan",
        astmCode     = 8,
        unitLabel    = "m",
        isAreaBased  = false,
        isLinear     = true
    ),
    LONG_TRANS_CRACK(
        displayName  = "Retak Memanjang/Melintang",
        astmCode     = 10,
        unitLabel    = "m",
        isAreaBased  = false,
        isLinear     = true
    ),
    RUTTING(
        displayName  = "Alur (Rutting)",
        astmCode     = 13,
        unitLabel    = "m²",    // rutting diukur per area
        isAreaBased  = true,
        isLinear     = false
    ),
    UTILITY_CUTPATCH(
        displayName  = "Tambalan Utilitas",
        astmCode     = 11,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),

    // ── Per unit (count) ─────────────────────────────────────────────────

    BUMPS_SAGS(
        displayName  = "Benjol/Cekung (Bumps & Sags)",
        astmCode     = 4,
        unitLabel    = "titik",
        isAreaBased  = false,
        isLinear     = false
    ),
    CORRUGATION(
        displayName  = "Bergelombang (Corrugation)",
        astmCode     = 5,
        unitLabel    = "m²",
        isAreaBased  = true,
        isLinear     = false
    ),
    POTHOLE(
        displayName  = "Lubang (Pothole)",
        astmCode     = 13,
        unitLabel    = "lubang",
        isAreaBased  = false,
        isLinear     = false
    );

    // ── Metadata untuk UI ─────────────────────────────────────────────────

    fun getSurveyorGuide(): String = when (this) {
        ALLIGATOR_CRACK      -> "📐 Ukur luas area retak buaya (m²)\n   Batas: tepi terluar pola retak"
        POTHOLE              -> "🔢 Hitung jumlah lubang (per satuan)\n   Input: jumlah lubang di segmen"
        RUTTING              -> "📐 Ukur luas area alur (m²)\n   Catat juga kedalaman (mm) di Catatan"
        LONG_TRANS_CRACK     -> "📏 Ukur panjang total retakan (m)\n   Gabungkan retak memanjang + melintang"
        DEPRESSION           -> "📐 Ukur luas area amblas (m²)\n   Catat kedalaman di kolom Catatan"
        BLEEDING             -> "📐 Ukur luas area kegemukan (m²)\n   Area yang terlihat mengkilap/berminyak"
        BLOCK_CRACK          -> "📐 Ukur luas total retak blok (m²)\n   Polygon terluar dari area retak"
        EDGE_CRACK           -> "📏 Ukur panjang retak di tepi jalan (m)\n   Max 0.5m dari tepi perkerasan"
        PATCHING_LARGE       -> "📐 Ukur luas total tambalan (m²)\n   Termasuk area di sekitar tambalan"
        RAVELING             -> "📐 Ukur luas pelepasan butir (m²)\n   Area yang tampak kasar/berpasir"
        SHOVING              -> "📐 Ukur luas jembul/deformasi (m²)\n   Area permukaannya naik tidak rata"
        SLIPPAGE_CRACK       -> "📐 Ukur luas retak selip (m²)\n   Retak berbentuk bulan sabit"
        CORRUGATION          -> "📐 Ukur luas bergelombang (m²)\n   Pola bergelombang tegak lurus"
        BUMPS_SAGS           -> "🔢 Hitung jumlah titik benjol/cekung\n   Tiap lokasi = 1 titik"
        POLISHED_AGGREGATE   -> "📐 Ukur luas agregat licin (m²)\n   Permukaan halus/terlihat aus"
        SWELLING             -> "📐 Ukur luas menggelembung (m²)\n   Area naik seperti gelombang panjang"
        LANE_SHOULDER_DROPOFF -> "📏 Ukur panjang penurunan bahu (m)\n   Elevasi bahu lebih rendah dari lajur"
        JOINT_REFLECTION_CRACK -> "📏 Ukur panjang retak sambungan (m)\n   Di atas sambungan lapis bawah"
        LONG_TRANS_CRACK     -> "📏 Ukur panjang total (m)\n   Semua retak memanjang + melintang"
        UTILITY_CUTPATCH     -> "📐 Ukur luas tambalan utilitas (m²)\n   Bekas galian PLN/PDAM/Telkom"
        else                 -> "📝 Masukkan nilai sesuai satuan ($unitLabel)"
    }

    fun getQuickPresets(): List<Pair<String, Double>> = when {
        this == POTHOLE || this == BUMPS_SAGS -> listOf(
            "1" to 1.0, "2" to 2.0, "5" to 5.0, "10" to 10.0
        )
        isLinear -> listOf(
            "5m" to 5.0, "10m" to 10.0, "25m" to 25.0, "50m" to 50.0
        )
        isAreaBased -> listOf(
            "1m²" to 1.0, "5m²" to 5.0, "10m²" to 10.0, "25m²" to 25.0
        )
        else -> listOf(
            "1" to 1.0, "5" to 5.0, "10" to 10.0, "20" to 20.0
        )
    }
}