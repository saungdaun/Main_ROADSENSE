package zaujaani.roadsensebasic.ui.summary

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PhotoItem — Model foto terpadu untuk semua mode survey.
 *
 * FIX Issue #3: Tambah metadata GPS + timestamp + distress info.
 * Sebelumnya caption tidak cukup informatif untuk audit trail.
 *
 * Caption format (untuk slider):
 *   "3/12  |  GPS: -6.1234,106.8765  |  STA 0+150  |  Lubang (HIGH)  |  09:41:23
 *    📝 Catatan surveyor"
 */
data class PhotoItem(
    val path: String,

    /**
     * Label utama, contoh:
     *   "Lubang (HIGH) | SDI"
     *   "Fatigue Cracking (HIGH) | PCI"
     *   "Foto Lapangan | Umum"
     */
    val label: String,

    /** STA / posisi, contoh: "STA 1+250" */
    val sta: String = "",

    /** Catatan surveyor (kosong untuk SDI DistressItem, berisi untuk PCI & General) */
    val notes: String = "",

    /** Sumber foto: dari input distress langsung atau FAB kamera umum */
    val source: Source = Source.DISTRESS,

    // ── Issue #3: Metadata GPS + waktu + distress info ────────────────────

    /** GPS latitude saat foto diambil (0.0 jika tidak ada GPS fix) */
    val latitude: Double = 0.0,

    /** GPS longitude saat foto diambil (0.0 jika tidak ada GPS fix) */
    val longitude: Double = 0.0,

    /** Epoch millis saat foto diambil, untuk timestamp di caption */
    val timestamp: Long = 0L,

    /**
     * Jenis kerusakan (hanya untuk source = DISTRESS).
     * Contoh: "Lubang", "Retak", "Fatigue Cracking"
     */
    val distressType: String = "",

    /**
     * Tingkat keparahan (hanya untuk source = DISTRESS).
     * Contoh: "LOW", "MEDIUM", "HIGH"
     */
    val severity: String = ""
) {
    enum class Source { DISTRESS, GENERAL }

    /**
     * Caption lengkap untuk photo slider.
     * Issue #3 fix: sertakan GPS, timestamp, dan distress info.
     */
    fun caption(index: Int, total: Int): String = buildString {
        // Baris 1: nomor + GPS (jika ada) + STA
        append("${index + 1}/$total")
        if (latitude != 0.0 && longitude != 0.0) {
            append("  |  GPS: ${"%.4f".format(latitude)},${"%.4f".format(longitude)}")
        }
        if (sta.isNotBlank()) append("  |  $sta")

        // Baris 2: jenis kerusakan + waktu
        appendLine()
        append(label)
        if (timestamp > 0L) {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            append("  |  ${fmt.format(Date(timestamp))}")
        }

        // Baris 3 (opsional): catatan
        if (notes.isNotBlank()) {
            appendLine()
            append("📝 $notes")
        }
    }
}