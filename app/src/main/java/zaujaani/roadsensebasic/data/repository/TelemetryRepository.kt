package zaujaani.roadsensebasic.data.repository

import kotlinx.coroutines.flow.Flow
import zaujaani.roadsensebasic.data.local.RoadSenseDatabase
import zaujaani.roadsensebasic.data.local.entity.TelemetryRaw
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelemetryRepository @Inject constructor(
    private val db: RoadSenseDatabase
) {
    private val telemetryDao = db.telemetryDao()

    // Insert
    suspend fun insertTelemetry(telemetry: TelemetryRaw) {
        telemetryDao.insert(telemetry)
    }

    suspend fun insertAll(telemetries: List<TelemetryRaw>) {
        telemetryDao.insertAll(telemetries)
    }

    // Get (Flow)
    fun getTelemetryForSessionFlow(sessionId: Long): Flow<List<TelemetryRaw>> {
        return telemetryDao.getTelemetryForSessionFlow(sessionId)
    }

    // Get (Once)
    suspend fun getTelemetryForSessionOnce(sessionId: Long): List<TelemetryRaw> {
        return telemetryDao.getTelemetryForSessionOnce(sessionId)
    }

    // Delete
    suspend fun deleteBySession(sessionId: Long) {
        telemetryDao.deleteBySession(sessionId)
    }

    suspend fun deleteOldTelemetry(sessionId: Long, cutoffTime: Long) {
        telemetryDao.deleteOldTelemetry(sessionId, cutoffTime)
    }
}