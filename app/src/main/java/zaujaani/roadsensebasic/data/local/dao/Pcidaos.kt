package zaujaani.roadsensebasic.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.entity.PCIDistressItem
import zaujaani.roadsensebasic.data.local.entity.SegmentPci

// ════════════════════════════════════════════════════════════════════
// SegmentPciDao
// ════════════════════════════════════════════════════════════════════

@Dao
interface SegmentPciDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: SegmentPci): Long

    @Update
    suspend fun updateSegment(segment: SegmentPci)

    @Query("""
        UPDATE segment_pci SET
            pciScore              = :pciScore,
            pciRating             = :pciRating,
            correctedDeductValue  = :cdv,
            distressCount         = :distressCount,
            dominantDistressType  = :dominantType,
            deductValuesJson      = :dvJson,
            updatedAt             = :updatedAt
        WHERE id = :segmentId
    """)
    suspend fun updatePciScore(
        segmentId: Long,
        pciScore: Int,
        pciRating: String,
        cdv: Double,
        distressCount: Int,
        dominantType: String,
        dvJson: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM segment_pci WHERE sessionId = :sessionId ORDER BY segmentIndex ASC")
    fun getSegmentsForSession(sessionId: Long): Flow<List<SegmentPci>>

    @Query("SELECT * FROM segment_pci WHERE sessionId = :sessionId ORDER BY segmentIndex ASC")
    suspend fun getSegmentsForSessionOnce(sessionId: Long): List<SegmentPci>

    @Query("SELECT * FROM segment_pci WHERE id = :segmentId")
    suspend fun getSegmentById(segmentId: Long): SegmentPci?

    @Query("""
        SELECT * FROM segment_pci
        WHERE sessionId = :sessionId
        AND segmentIndex = :segmentIndex
        LIMIT 1
    """)
    suspend fun getSegmentByIndex(sessionId: Long, segmentIndex: Int): SegmentPci?

    @Query("SELECT COUNT(*) FROM segment_pci WHERE sessionId = :sessionId")
    suspend fun getSegmentCount(sessionId: Long): Int

    @Query("""
        SELECT AVG(pciScore) FROM segment_pci
        WHERE sessionId = :sessionId AND pciScore >= 0
    """)
    suspend fun getAveragePci(sessionId: Long): Double?

    @Query("DELETE FROM segment_pci WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}

// ════════════════════════════════════════════════════════════════════
// PCIDistressItemDao
// ════════════════════════════════════════════════════════════════════

@Dao
interface PCIDistressItemDao {

    @Insert
    suspend fun insert(item: PCIDistressItem): Long

    @Update
    suspend fun update(item: PCIDistressItem)

    @Delete
    suspend fun delete(item: PCIDistressItem)

    @Query("SELECT * FROM pci_distress_items WHERE segmentId = :segmentId ORDER BY createdAt ASC")
    fun getItemsForSegment(segmentId: Long): Flow<List<PCIDistressItem>>

    @Query("SELECT * FROM pci_distress_items WHERE segmentId = :segmentId ORDER BY createdAt ASC")
    suspend fun getItemsForSegmentOnce(segmentId: Long): List<PCIDistressItem>

    @Query("SELECT * FROM pci_distress_items WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getItemsForSession(sessionId: Long): List<PCIDistressItem>

    @Query("SELECT * FROM pci_distress_items WHERE id = :itemId")
    suspend fun getById(itemId: Long): PCIDistressItem?

    @Query("DELETE FROM pci_distress_items WHERE segmentId = :segmentId")
    suspend fun deleteBySegment(segmentId: Long)

    @Query("DELETE FROM pci_distress_items WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}