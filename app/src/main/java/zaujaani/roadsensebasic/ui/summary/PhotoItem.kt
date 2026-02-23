package zaujaani.roadsensebasic.ui.summary

/**
 * PhotoItem — Model foto terpadu untuk semua mode survey.
 *
 * Digunakan di photo slider agar bisa menampilkan:
 * - Judul/caption yang informatif
 * - STA (posisi jalan)
 * - Notes dari surveyor
 * - Sumber foto (distress langsung atau FAB kamera umum)
 */
data class PhotoItem(
    val path: String,

    /** Label utama, contoh:
     *  "Lubang Kecil (Medium) | SDI"
     *  "Fatigue Cracking (High) | PCI"
     *  "Foto Lapangan | Umum"
     */
    val label: String,

    /** STA / posisi, contoh: "STA 1+250" */
    val sta: String = "",

    /** Catatan surveyor, bisa kosong */
    val notes: String = "",

    /** Sumber foto: DISTRESS atau GENERAL */
    val source: Source = Source.DISTRESS
) {
    enum class Source { DISTRESS, GENERAL }

    /** Caption lengkap untuk slider */
    fun caption(index: Int, total: Int): String {
        val base = buildString {
            append("${index + 1}/$total")
            if (sta.isNotBlank()) append("  •  $sta")
            append("\n$label")
            if (notes.isNotBlank()) append("\n📝 $notes")
        }
        return base
    }
}