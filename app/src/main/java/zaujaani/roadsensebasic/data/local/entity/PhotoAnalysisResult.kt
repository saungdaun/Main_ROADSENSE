package zaujaani.roadsensebasic.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * PhotoAnalysisResult
 *
 * Menyimpan hasil analisis AI untuk satu foto.
 * - Primary key = photoPath (satu foto = satu hasil analisis)
 * - analysisJson = raw JSON dari Claude Vision API (disimpan utuh untuk debug)
 * - Field-field flat = hasil parse dari JSON (untuk query & tampilan cepat tanpa parse ulang)
 *
 * Status alur:
 *   PENDING → ANALYZING → DONE
 *                       ↘ FAILED
 *                       ↘ SKIPPED (foto tidak bisa dibaca / terlalu gelap)
 */
@Entity(tableName = "photo_analysis")
data class PhotoAnalysisResult(

    @PrimaryKey
    val photoPath: String,              // path absolut file foto (FK logis ke road_events.value)

    val sessionId: Long,                // FK ke survey_sessions.id

    // ── Status ──────────────────────────────────────────────────────────
    val status: AnalysisStatus = AnalysisStatus.PENDING,

    // ── Hasil analisis (terisi saat status = DONE) ───────────────────────
    val analysisJson: String = "",      // raw JSON dari API (untuk debug / export)

    val overallCondition: String = "",  // "BAIK" | "SEDANG" | "RUSAK_RINGAN" | "RUSAK_BERAT"
    val overallConditionScore: Int = 0, // 0–100 (semakin tinggi = semakin rusak)

    val detectedTypes: String = "",     // comma-separated: "POTHOLE,CRACK"
    val dominantSeverity: String = "",  // "LOW" | "MEDIUM" | "HIGH"

    val confidenceScore: Float = 0f,    // 0.0–1.0, seberapa yakin AI

    val aiDescription: String = "",     // deskripsi narasi dari AI (Bahasa Indonesia)
    val aiRecommendation: String = "",  // rekomendasi penanganan dari AI

    // ── Override manual oleh surveyor ───────────────────────────────────
    val isManualOverride: Boolean = false,
    val manualCondition: String = "",   // kondisi yang dikoreksi surveyor
    val manualNotes: String = "",       // catatan koreksi surveyor

    // ── Error handling ───────────────────────────────────────────────────
    val errorMessage: String = "",      // pesan error jika status = FAILED

    // ── Timestamps ───────────────────────────────────────────────────────
    val createdAt: Long = System.currentTimeMillis(),
    val analyzedAt: Long = 0L           // di-set saat analisis selesai
)

/**
 * Status proses analisis satu foto.
 */
enum class AnalysisStatus {
    PENDING,    // belum dianalisis (baru masuk antrian)
    ANALYZING,  // sedang dikirim ke API
    DONE,       // berhasil dianalisis
    FAILED,     // gagal (network error, API error, dsb)
    SKIPPED     // dilewat (foto corrupt / terlalu gelap / file tidak ada)
}