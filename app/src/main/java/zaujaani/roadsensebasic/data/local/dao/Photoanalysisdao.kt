package zaujaani.roadsensebasic.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.entity.AnalysisStatus
import zaujaani.roadsensebasic.data.local.entity.PhotoAnalysisResult

@Dao
interface PhotoAnalysisDao {

    // ── INSERT / UPDATE ───────────────────────────────────────────────────

    /**
     * Insert baru atau replace jika photoPath sudah ada.
     * Dipakai saat pertama kali mendaftarkan foto ke antrian.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: PhotoAnalysisResult)

    /**
     * Insert batch — untuk mendaftarkan semua foto satu session sekaligus.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(results: List<PhotoAnalysisResult>)

    /**
     * Update status + pesan error (dipakai saat ANALYZING, FAILED, SKIPPED).
     */
    @Query("""
        UPDATE photo_analysis 
        SET status = :status, errorMessage = :errorMessage
        WHERE photoPath = :photoPath
    """)
    suspend fun updateStatus(
        photoPath: String,
        status: AnalysisStatus,
        errorMessage: String = ""
    )

    /**
     * Update hasil analisis lengkap setelah API sukses.
     */
    @Query("""
        UPDATE photo_analysis SET
            status             = :status,
            analysisJson       = :analysisJson,
            overallCondition   = :overallCondition,
            overallConditionScore = :overallConditionScore,
            detectedTypes      = :detectedTypes,
            dominantSeverity   = :dominantSeverity,
            confidenceScore    = :confidenceScore,
            aiDescription      = :aiDescription,
            aiRecommendation   = :aiRecommendation,
            errorMessage       = '',
            analyzedAt         = :analyzedAt
        WHERE photoPath = :photoPath
    """)
    suspend fun updateAnalysisResult(
        photoPath: String,
        status: AnalysisStatus,
        analysisJson: String,
        overallCondition: String,
        overallConditionScore: Int,
        detectedTypes: String,
        dominantSeverity: String,
        confidenceScore: Float,
        aiDescription: String,
        aiRecommendation: String,
        analyzedAt: Long
    )

    /**
     * Simpan koreksi manual dari surveyor.
     */
    @Query("""
        UPDATE photo_analysis SET
            isManualOverride = 1,
            manualCondition  = :manualCondition,
            manualNotes      = :manualNotes
        WHERE photoPath = :photoPath
    """)
    suspend fun saveManualOverride(
        photoPath: String,
        manualCondition: String,
        manualNotes: String
    )

    /**
     * Hapus override manual — kembalikan ke hasil AI.
     */
    @Query("""
        UPDATE photo_analysis SET
            isManualOverride = 0,
            manualCondition  = '',
            manualNotes      = ''
        WHERE photoPath = :photoPath
    """)
    suspend fun clearManualOverride(photoPath: String)

    // ── QUERY ─────────────────────────────────────────────────────────────

    /** Semua hasil analisis satu session, diobserve sebagai Flow. */
    @Query("SELECT * FROM photo_analysis WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getAllBySession(sessionId: Long): Flow<List<PhotoAnalysisResult>>

    /** Satu foto berdasarkan path. */
    @Query("SELECT * FROM photo_analysis WHERE photoPath = :photoPath")
    suspend fun getByPath(photoPath: String): PhotoAnalysisResult?

    /** Semua foto yang masih PENDING dalam satu session (untuk dilanjutkan). */
    @Query("""
        SELECT * FROM photo_analysis 
        WHERE sessionId = :sessionId AND status = 'PENDING'
        ORDER BY createdAt ASC
    """)
    suspend fun getPendingBySession(sessionId: Long): List<PhotoAnalysisResult>

    /** Jumlah foto per status dalam satu session — untuk progress bar. */
    @Query("""
        SELECT status, COUNT(*) as count 
        FROM photo_analysis 
        WHERE sessionId = :sessionId 
        GROUP BY status
    """)
    suspend fun getStatusCountBySession(sessionId: Long): List<StatusCount>

    /** Jumlah total foto dalam satu session yang sudah terdaftar. */
    @Query("SELECT COUNT(*) FROM photo_analysis WHERE sessionId = :sessionId")
    suspend fun getTotalCount(sessionId: Long): Int

    /** Jumlah foto yang sudah DONE dalam satu session. */
    @Query("""
        SELECT COUNT(*) FROM photo_analysis 
        WHERE sessionId = :sessionId AND status = 'DONE'
    """)
    suspend fun getDoneCount(sessionId: Long): Int

    /**
     * Agregat kondisi seluruh session — untuk ringkasan laporan.
     * Mengembalikan hitungan per kondisi jalan yang terdeteksi AI.
     */
    @Query("""
        SELECT overallCondition, COUNT(*) as count
        FROM photo_analysis
        WHERE sessionId = :sessionId AND status = 'DONE'
        GROUP BY overallCondition
    """)
    suspend fun getConditionSummary(sessionId: Long): List<ConditionCount>

    // ── DELETE ────────────────────────────────────────────────────────────

    /** Hapus semua hasil analisis satu session (saat session dihapus). */
    @Query("DELETE FROM photo_analysis WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    /** Reset semua yang FAILED / ANALYZING ke PENDING (untuk retry). */
    @Query("""
        UPDATE photo_analysis 
        SET status = 'PENDING', errorMessage = ''
        WHERE sessionId = :sessionId AND status IN ('FAILED', 'ANALYZING')
    """)
    suspend fun resetFailedToPending(sessionId: Long)
}

// ── Helper data classes untuk hasil query agregat ─────────────────────────

data class StatusCount(
    val status: AnalysisStatus,
    val count: Int
)

data class ConditionCount(
    val overallCondition: String,
    val count: Int
)