package zaujaani.roadsensebasic.data.repository

import android.content.Context
import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.dao.ConditionCount
import zaujaani.roadsensebasic.data.local.dao.PhotoAnalysisDao
import zaujaani.roadsensebasic.data.local.dao.StatusCount
import zaujaani.roadsensebasic.data.local.entity.AnalysisStatus
import zaujaani.roadsensebasic.data.local.entity.PhotoAnalysisResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PhotoAnalysisRepository
 *
 * Single source of truth untuk semua operasi terkait analisis AI foto.
 * Layer ini yang tahu cara:
 *   - Mendaftarkan foto ke antrian analisis
 *   - Menyimpan & membaca hasil dari DB
 *   - Mengelola retry, override manual, dan agregat laporan
 *
 * ClaudeVisionRepository (network) dan PhotoAnalysisViewModel (UI)
 * keduanya hanya bicara ke sini — tidak langsung ke DAO.
 */
@Singleton
class PhotoAnalysisRepository @Inject constructor(
    private val dao: PhotoAnalysisDao,
    private val context: Context
) {

    // ── READ ──────────────────────────────────────────────────────────────

    /**
     * Observe semua hasil analisis satu session sebagai Flow.
     * UI menggunakan ini untuk update real-time saat analisis berjalan.
     */
    fun getAnalysisResultsForSession(sessionId: Long): Flow<List<PhotoAnalysisResult>> =
        dao.getAllBySession(sessionId)

    /** Ambil satu hasil berdasarkan path foto. Nullable jika belum pernah dianalisis. */
    suspend fun getResultByPath(photoPath: String): PhotoAnalysisResult? =
        dao.getByPath(photoPath)

    /** Ambil semua foto yang masih PENDING dalam satu session (untuk antrian). */
    suspend fun getPendingPhotos(sessionId: Long): List<PhotoAnalysisResult> =
        dao.getPendingBySession(sessionId)

    /** Progress: berapa foto sudah DONE dari total. */
    suspend fun getProgress(sessionId: Long): Pair<Int, Int> {
        val done  = dao.getDoneCount(sessionId)
        val total = dao.getTotalCount(sessionId)
        return done to total
    }

    /** Hitungan per status untuk progress bar detail. */
    suspend fun getStatusCounts(sessionId: Long): List<StatusCount> =
        dao.getStatusCountBySession(sessionId)

    /**
     * Ringkasan kondisi untuk laporan: berapa foto per kondisi jalan.
     * Contoh hasil: [("RUSAK_RINGAN", 5), ("BAIK", 3), ("SEDANG", 2)]
     */
    suspend fun getConditionSummary(sessionId: Long): List<ConditionCount> =
        dao.getConditionSummary(sessionId)

    /**
     * Apakah semua foto sudah selesai dianalisis (DONE / FAILED / SKIPPED)?
     * Dipakai untuk menentukan apakah tombol "Analisis" sudah bisa di-disable.
     */
    suspend fun isAnalysisComplete(sessionId: Long): Boolean {
        val counts = dao.getStatusCountBySession(sessionId)
        val pending   = counts.find { it.status == AnalysisStatus.PENDING }?.count ?: 0
        val analyzing = counts.find { it.status == AnalysisStatus.ANALYZING }?.count ?: 0
        return pending == 0 && analyzing == 0
    }

    // ── WRITE — Antrian ───────────────────────────────────────────────────

    /**
     * Daftarkan semua foto dari satu session ke antrian PENDING.
     * Dipanggil satu kali saat user tap "Analisis AI".
     *
     * Hanya mendaftarkan foto yang:
     * 1. File-nya benar-benar ada di storage
     * 2. Belum pernah didaftarkan sebelumnya (status != DONE)
     *
     * @return jumlah foto yang berhasil didaftarkan
     */
    suspend fun enqueuePhotosForSession(
        sessionId: Long,
        photoPaths: List<String>
    ): Int {
        val toInsert = photoPaths.filter { path ->
            // Validasi file ada
            if (!File(path).exists()) return@filter false
            // Skip yang sudah DONE — tidak perlu analisis ulang
            val existing = dao.getByPath(path)
            existing == null || existing.status == AnalysisStatus.FAILED
        }.map { path ->
            PhotoAnalysisResult(
                photoPath = path,
                sessionId = sessionId,
                status    = AnalysisStatus.PENDING,
                createdAt = System.currentTimeMillis()
            )
        }
        if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
        return toInsert.size
    }

    // ── WRITE — Status updates (dipanggil oleh ClaudeVisionRepository) ───

    /** Tandai foto sebagai sedang diproses. */
    suspend fun markAsAnalyzing(photoPath: String) =
        dao.updateStatus(photoPath, AnalysisStatus.ANALYZING)

    /** Tandai foto sebagai dilewati (file corrupt / terlalu gelap). */
    suspend fun markAsSkipped(photoPath: String, reason: String) =
        dao.updateStatus(photoPath, AnalysisStatus.SKIPPED, reason)

    /** Tandai foto sebagai gagal dianalisis. */
    suspend fun markAsFailed(photoPath: String, errorMessage: String) =
        dao.updateStatus(photoPath, AnalysisStatus.FAILED, errorMessage)

    /**
     * Simpan hasil analisis sukses dari Claude API.
     * Dipanggil oleh ClaudeVisionRepository setelah parsing JSON berhasil.
     */
    suspend fun saveAnalysisResult(
        photoPath: String,
        analysisJson: String,
        overallCondition: String,
        overallConditionScore: Int,
        detectedTypes: String,
        dominantSeverity: String,
        confidenceScore: Float,
        aiDescription: String,
        aiRecommendation: String
    ) {
        dao.updateAnalysisResult(
            photoPath             = photoPath,
            status                = AnalysisStatus.DONE,
            analysisJson          = analysisJson,
            overallCondition      = overallCondition,
            overallConditionScore = overallConditionScore,
            detectedTypes         = detectedTypes,
            dominantSeverity      = dominantSeverity,
            confidenceScore       = confidenceScore,
            aiDescription         = aiDescription,
            aiRecommendation      = aiRecommendation,
            analyzedAt            = System.currentTimeMillis()
        )
    }

    // ── WRITE — Override manual ───────────────────────────────────────────

    /** Surveyor mengoreksi hasil AI. */
    suspend fun saveManualOverride(
        photoPath: String,
        manualCondition: String,
        manualNotes: String
    ) = dao.saveManualOverride(photoPath, manualCondition, manualNotes)

    /** Kembalikan ke hasil AI (hapus override). */
    suspend fun clearManualOverride(photoPath: String) =
        dao.clearManualOverride(photoPath)

    // ── WRITE — Maintenance ───────────────────────────────────────────────

    /**
     * Reset semua FAILED dan ANALYZING ke PENDING untuk satu session.
     * Dipanggil saat user tap "Coba Lagi".
     */
    suspend fun retryFailed(sessionId: Long) =
        dao.resetFailedToPending(sessionId)

    /** Hapus semua hasil analisis saat session dihapus. */
    suspend fun deleteBySession(sessionId: Long) =
        dao.deleteBySession(sessionId)

    // ── HELPER ────────────────────────────────────────────────────────────

    /**
     * Kondisi efektif satu foto:
     * - Jika ada override manual → pakai manualCondition
     * - Jika tidak → pakai overallCondition dari AI
     */
    fun getEffectiveCondition(result: PhotoAnalysisResult): String =
        if (result.isManualOverride && result.manualCondition.isNotBlank())
            result.manualCondition
        else
            result.overallCondition
}