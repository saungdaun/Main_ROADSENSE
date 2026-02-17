package zaujaani.roadsensebasic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.entity.TelemetryRaw

@Dao
interface TelemetryDao {

    @Insert
    suspend fun insert(telemetry: TelemetryRaw)

    @Insert
    suspend fun insertAll(telemetries: List<TelemetryRaw>)

    @Query("SELECT * FROM telemetry_raw WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getTelemetryForSessionFlow(sessionId: Long): Flow<List<TelemetryRaw>>

    @Query("SELECT * FROM telemetry_raw WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getTelemetryForSessionOnce(sessionId: Long): List<TelemetryRaw>

    @Query("DELETE FROM telemetry_raw WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("DELETE FROM telemetry_raw WHERE sessionId = :sessionId AND timestamp < :cutoffTime")
    suspend fun deleteOldTelemetry(sessionId: Long, cutoffTime: Long)
}