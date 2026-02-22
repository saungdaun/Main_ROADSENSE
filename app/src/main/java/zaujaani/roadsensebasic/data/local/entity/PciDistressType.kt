package zaujaani.roadsensebasic.data.local.entity

/**
 * PCIDistressType — 19 jenis kerusakan ASTM D6433
 *
 * Referensi:
 * ASTM D6433-11
 * Shahin (1994) PCI Manual
 */
enum class PCIDistressType(
    val displayName: String,
    val astmCode: Int,
    val unitLabel: String,
    val isAreaBased: Boolean,
    val isLinear: Boolean
) {

    // ───────────── AREA BASED (m²) ─────────────

    ALLIGATOR_CRACK("Retak Buaya", 1, "m²", true, false),
    BLEEDING("Kegemukan", 2, "m²", true, false),
    BLOCK_CRACK("Retak Blok", 3, "m²", true, false),
    CORRUGATION("Bergelombang", 5, "m²", true, false),
    DEPRESSION("Amblas", 6, "m²", true, false),
    PATCHING_LARGE("Tambalan Besar", 11, "m²", true, false),
    POLISHED_AGGREGATE("Agregat Licin", 12, "m²", true, false),
    RAVELING("Pelepasan Butir", 19, "m²", true, false),
    SHOVING("Jembul", 15, "m²", true, false),
    SLIPPAGE_CRACK("Retak Selip", 16, "m²", true, false),
    SWELLING("Menggelembung", 18, "m²", true, false),
    RUTTING("Alur (Rutting)", 13, "m²", true, false),
    UTILITY_CUTPATCH("Tambalan Utilitas", 11, "m²", true, false),

    // ───────────── LINEAR (m) ─────────────

    EDGE_CRACK("Retak Tepi", 7, "m", false, true),
    JOINT_REFLECTION_CRACK("Retak Sambungan", 10, "m", false, true),
    LANE_SHOULDER_DROPOFF("Penurunan Bahu", 8, "m", false, true),
    LONG_TRANS_CRACK("Retak Memanjang/Melintang", 9, "m", false, true),

    // ───────────── COUNT ─────────────

    BUMPS_SAGS("Benjol/Cekung", 4, "titik", false, false),
    POTHOLE("Lubang", 14, "lubang", false, false);

    // ───────────── Helper UI ─────────────

    fun getAstmLabel(): String {
        return "ASTM D6433 - Code $astmCode"
    }

    fun getSurveyorGuide(): String = when (this) {

        ALLIGATOR_CRACK ->
            "📐 Ukur luas retak buaya (m²)\nBatas: tepi pola retak"

        BLEEDING ->
            "📐 Ukur luas kegemukan (m²)\nArea tampak mengkilap"

        BLOCK_CRACK ->
            "📐 Ukur luas retak blok (m²)"

        CORRUGATION ->
            "📐 Ukur luas bergelombang (m²)"

        DEPRESSION ->
            "📐 Ukur luas amblas (m²)\nCatat kedalaman"

        PATCHING_LARGE ->
            "📐 Ukur luas tambalan (m²)"

        POLISHED_AGGREGATE ->
            "📐 Ukur luas agregat licin (m²)"

        RAVELING ->
            "📐 Ukur luas pelepasan butir (m²)"

        SHOVING ->
            "📐 Ukur luas jembul (m²)"

        SLIPPAGE_CRACK ->
            "📐 Ukur luas retak selip (m²)"

        SWELLING ->
            "📐 Ukur luas menggelembung (m²)"

        RUTTING ->
            "📐 Ukur luas alur (m²)\nCatat kedalaman (mm)"

        UTILITY_CUTPATCH ->
            "📐 Ukur luas tambalan utilitas (m²)"

        EDGE_CRACK ->
            "📏 Ukur panjang retak tepi (m)"

        JOINT_REFLECTION_CRACK ->
            "📏 Ukur panjang retak sambungan (m)"

        LANE_SHOULDER_DROPOFF ->
            "📏 Ukur panjang penurunan bahu (m)"

        LONG_TRANS_CRACK ->
            "📏 Ukur total retak memanjang + melintang (m)"

        BUMPS_SAGS ->
            "🔢 Hitung jumlah titik benjol/cekung"

        POTHOLE ->
            "🔢 Hitung jumlah lubang"
    }

    fun getQuickPresets(): List<Pair<String, Double>> = when {
        this == POTHOLE || this == BUMPS_SAGS -> listOf(
            "1" to 1.0,
            "2" to 2.0,
            "5" to 5.0,
            "10" to 10.0
        )

        isLinear -> listOf(
            "5m" to 5.0,
            "10m" to 10.0,
            "25m" to 25.0,
            "50m" to 50.0
        )

        isAreaBased -> listOf(
            "1m²" to 1.0,
            "5m²" to 5.0,
            "10m²" to 10.0,
            "25m²" to 25.0
        )

        else -> listOf(
            "1" to 1.0
        )
    }
}