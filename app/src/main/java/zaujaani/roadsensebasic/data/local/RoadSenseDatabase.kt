package zaujaani.roadsensebasic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import zaujaani.roadsensebasic.data.local.dao.SegmentDao
import zaujaani.roadsensebasic.data.local.dao.SessionDao
import zaujaani.roadsensebasic.data.local.dao.TelemetryDao
import zaujaani.roadsensebasic.data.local.entity.RoadSegment
import zaujaani.roadsensebasic.data.local.entity.SurveySession
import zaujaani.roadsensebasic.data.local.entity.TelemetryRaw

@Database(
    entities = [TelemetryRaw::class, RoadSegment::class, SurveySession::class],
    version = 4, // dinaikkan dari 3 ke 4
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

        /** Migrasi v1 → v2: tambah kolom GPS ke road_segments */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE road_segments ADD COLUMN startLat REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE road_segments ADD COLUMN startLng REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE road_segments ADD COLUMN endLat REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE road_segments ADD COLUMN endLng REAL NOT NULL DEFAULT 0.0")
            }
        }

        /** Migrasi v2 → v3: tambah kolom surveyor & road name ke survey_sessions */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN surveyorName TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN roadName TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN startLat REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN startLng REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN endLat REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE survey_sessions ADD COLUMN endLng REAL NOT NULL DEFAULT 0.0")
            }
        }

        /** Migrasi v3 → v4: tambah kolom vibrationX, vibrationY ke telemetry_raw */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Tambah kolom baru dengan default 0.0 (karena data lama tidak punya nilai)
                database.execSQL("ALTER TABLE telemetry_raw ADD COLUMN vibrationX REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE telemetry_raw ADD COLUMN vibrationY REAL NOT NULL DEFAULT 0.0")
            }
        }

        fun getInstance(context: Context): RoadSenseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RoadSenseDatabase::class.java,
                    "roadsense_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4) // tambahkan migrasi baru
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}