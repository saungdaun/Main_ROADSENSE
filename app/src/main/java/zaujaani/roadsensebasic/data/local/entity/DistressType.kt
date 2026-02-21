package zaujaani.roadsensebasic.data.local.entity

/**
 * DistressType — jenis kerusakan jalan untuk survey SDI.
 * SCREAMING_SNAKE_CASE sesuai konvensi Kotlin enum.
 *
 * Referensi: PD T-05-2005-B Bina Marga & SNI 03-3441-1994
 */
enum class DistressType(val displayName: String) {

    CRACK("Retak"),
    POTHOLE("Lubang"),
    RUTTING("Alur"),
    DEPRESSION("Ambles"),
    SPALLING("Pengelupasan"),
    RAVELING("Lepas Agregat"),
    OTHER("Lainnya");

    /** Satuan ukuran untuk UI label */
    val isAreaBased: Boolean
        get() = when (this) {
            POTHOLE, SPALLING, RAVELING, DEPRESSION -> true
            CRACK, RUTTING, OTHER -> false
        }

    val unit: String
        get() = if (isAreaBased) "m²" else "m"

    /**
     * Panduan singkat untuk surveyor.
     * Dipanggil sebagai fungsi agar konsisten dengan pola
     * pemanggilan di DistressBottomSheet: type.getSurveyorGuide()
     */
    fun getSurveyorGuide(): String = when (this) {
        CRACK      -> "📏 Ukur panjang total retakan dalam meter (m)\n   Contoh: 5.5 m"
        POTHOLE    -> "📐 Hitung luas: P × L lubang → m²\n   Contoh: 0.5m × 0.4m = 0.20 m²"
        RUTTING    -> "📏 Ukur panjang jalur alur (m)\n   Biasanya = panjang segmen (100m)"
        DEPRESSION -> "📐 Estimasi luas area yang ambles (m²)\n   Contoh: 2.0 × 1.5 = 3.0 m²"
        SPALLING   -> "📐 Estimasi luas area mengelupas (m²)\n   Contoh: 1.0 × 2.0 = 2.0 m²"
        RAVELING   -> "📐 Estimasi luas agregat lepas (m²)\n   Contoh: area kasar / berpasir"
        OTHER      -> "📝 Masukkan nilai estimasi & catat detail\n   di kolom catatan"
    }

    /**
     * Preset cepat per tipe — Pair<label, nilai Double>
     * Dipanggil sebagai fungsi agar konsisten dengan pola
     * pemanggilan di DistressBottomSheet: type.getQuickPresets()
     */
    fun getQuickPresets(): List<Pair<String, Double>> = when (this) {
        CRACK      -> listOf("5m" to 5.0,    "10m" to 10.0,  "25m" to 25.0,  "50m" to 50.0)
        POTHOLE    -> listOf("0.1" to 0.1,   "0.25" to 0.25, "0.5" to 0.5,   "1.0" to 1.0)
        RUTTING    -> listOf("10m" to 10.0,  "25m" to 25.0,  "50m" to 50.0,  "100m" to 100.0)
        DEPRESSION -> listOf("0.5" to 0.5,   "1.0" to 1.0,   "2.0" to 2.0,   "5.0" to 5.0)
        SPALLING   -> listOf("1.0" to 1.0,   "2.0" to 2.0,   "5.0" to 5.0,   "10.0" to 10.0)
        RAVELING   -> listOf("2.0" to 2.0,   "5.0" to 5.0,   "10.0" to 10.0, "20.0" to 20.0)
        OTHER      -> listOf("1.0" to 1.0,   "5.0" to 5.0,   "10.0" to 10.0, "20.0" to 20.0)
    }
}