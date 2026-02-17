package zaujaani.roadsensebasic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import zaujaani.roadsensebasic.data.local.dao.SegmentDao
import zaujaani.roadsensebasic.data.local.dao.SessionDao
import zaujaani.roadsensebasic.data.local.dao.TelemetryDao
import zaujaani.roadsensebasic.data.local.entity.RoadSegment
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.local.entity.TelemetryRaw

@Database(
    entities = [TelemetryRaw::class, RoadSegment::class, SurveySession::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class RoadSenseDatabase : RoomDatabase() {
    abstract fun telemetryDao(): TelemetryDao
    abstract fun segmentDao(): SegmentDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var INSTANCE: RoadSenseDatabase? = null

        fun getInstance(context: Context): RoadSenseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RoadSenseDatabase::class.java,
                    "roadsense_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}