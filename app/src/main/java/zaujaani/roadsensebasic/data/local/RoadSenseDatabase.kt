package zaujaani.roadsensebasic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import zaujaani.roadsensebasic.data.local.dao.*
import zaujaani.roadsensebasic.data.local.entity.*

@Database(
    entities = [
        TelemetryRaw::class,
        SurveySession::class,
        RoadEvent::class,
        SegmentSdi::class,
        DistressItem::class,
        RoadSegment::class,
        PhotoAnalysisResult::class,
        SegmentPci::class,          // ← BARU
        PCIDistressItem::class      // ← BARU
    ],
    version = 9,                    // ← naik dari 8
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class RoadSenseDatabase : RoomDatabase() {

    abstract fun telemetryDao(): TelemetryDao
    abstract fun sessionDao(): SessionDao
    abstract fun eventDao(): EventDao
    abstract fun segmentSdiDao(): SegmentSdiDao
    abstract fun distressItemDao(): DistressItemDao
    abstract fun photoAnalysisDao(): PhotoAnalysisDao
    abstract fun segmentDao(): SegmentDao
    abstract fun segmentPciDao(): SegmentPciDao         // ← BARU
    abstract fun pciDistressItemDao(): PCIDistressItemDao // ← BARU

    companion object {

        @Volatile
        private var INSTANCE: RoadSenseDatabase? = null

        // ── Migrasi lama — tidak diubah sama sekali ───────────────────────

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE road_segments ADD COLUMN startLat REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE road_segments ADD COLUMN startLng REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE road_segments ADD COLUMN endLat REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE road_segments ADD COLUMN endLng REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN surveyorName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN roadName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN startLat REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN startLng REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN endLat REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN endLng REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE telemetry_raw ADD COLUMN vibrationX REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE telemetry_raw ADD COLUMN vibrationY REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `road_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `distance` REAL NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `notes` TEXT
                    )
                """)
                db.execSQL("DROP TABLE IF EXISTS `road_segments`")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'GENERAL'")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `segment_sdi` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `segmentIndex` INTEGER NOT NULL,
                        `startSta` TEXT NOT NULL,
                        `endSta` TEXT NOT NULL,
                        `sdiScore` INTEGER NOT NULL DEFAULT 0,
                        `distressCount` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `distress_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `segmentId` INTEGER NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `severity` TEXT NOT NULL,
                        `lengthOrArea` REAL NOT NULL,
                        `photoPath` TEXT NOT NULL,
                        `audioPath` TEXT NOT NULL,
                        `gpsLat` REAL NOT NULL,
                        `gpsLng` REAL NOT NULL,
                        `sta` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_segment_sdi_session ON segment_sdi(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_distress_segment ON distress_items(segmentId)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN avgSdi INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `photo_analysis` (
                        `photoPath`              TEXT NOT NULL PRIMARY KEY,
                        `sessionId`              INTEGER NOT NULL,
                        `status`                 TEXT NOT NULL DEFAULT 'PENDING',
                        `analysisJson`           TEXT NOT NULL DEFAULT '',
                        `overallCondition`       TEXT NOT NULL DEFAULT '',
                        `overallConditionScore`  INTEGER NOT NULL DEFAULT 0,
                        `detectedTypes`          TEXT NOT NULL DEFAULT '',
                        `dominantSeverity`       TEXT NOT NULL DEFAULT '',
                        `confidenceScore`        REAL NOT NULL DEFAULT 0.0,
                        `aiDescription`          TEXT NOT NULL DEFAULT '',
                        `aiRecommendation`       TEXT NOT NULL DEFAULT '',
                        `isManualOverride`       INTEGER NOT NULL DEFAULT 0,
                        `manualCondition`        TEXT NOT NULL DEFAULT '',
                        `manualNotes`            TEXT NOT NULL DEFAULT '',
                        `errorMessage`           TEXT NOT NULL DEFAULT '',
                        `createdAt`              INTEGER NOT NULL,
                        `analyzedAt`             INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_photo_analysis_session ON photo_analysis(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_photo_analysis_status ON photo_analysis(status)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `road_segments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `startDistance` REAL NOT NULL,
                        `endDistance` REAL NOT NULL,
                        `avgVibration` REAL NOT NULL,
                        `conditionAuto` TEXT NOT NULL,
                        `surfaceType` TEXT NOT NULL,
                        `confidence` INTEGER NOT NULL DEFAULT 0,
                        `sdiScore` INTEGER NOT NULL DEFAULT 0,
                        `notes` TEXT NOT NULL DEFAULT '',
                        `photoPath` TEXT NOT NULL DEFAULT '',
                        `audioPath` TEXT NOT NULL DEFAULT '',
                        `startLat` REAL NOT NULL DEFAULT 0.0,
                        `startLng` REAL NOT NULL DEFAULT 0.0,
                        `endLat` REAL NOT NULL DEFAULT 0.0,
                        `endLng` REAL NOT NULL DEFAULT 0.0,
                        `createdAt` INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_road_segments_session ON road_segments(sessionId)")
            }
        }

        // ── MIGRATION 8 → 9: PCI + kolom avgPci ──────────────────────────
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // 1. avgPci di survey_sessions (-1 = belum dihitung / bukan sesi PCI)
                db.execSQL("ALTER TABLE survey_sessions ADD COLUMN avgPci INTEGER NOT NULL DEFAULT -1")

                // 2. Tabel segment_pci (50m per segmen, ASTM D6433)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `segment_pci` (
                        `id`                    INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId`             INTEGER NOT NULL,
                        `segmentIndex`          INTEGER NOT NULL,
                        `startSta`              TEXT NOT NULL,
                        `endSta`                TEXT NOT NULL,
                        `startLat`              REAL NOT NULL DEFAULT 0.0,
                        `startLng`              REAL NOT NULL DEFAULT 0.0,
                        `segmentLengthM`        REAL NOT NULL DEFAULT 50.0,
                        `laneWidthM`            REAL NOT NULL DEFAULT 3.7,
                        `sampleAreaM2`          REAL NOT NULL DEFAULT 185.0,
                        `pciScore`              INTEGER NOT NULL DEFAULT -1,
                        `pciRating`             TEXT NOT NULL DEFAULT '',
                        `correctedDeductValue`  REAL NOT NULL DEFAULT 0.0,
                        `distressCount`         INTEGER NOT NULL DEFAULT 0,
                        `dominantDistressType`  TEXT NOT NULL DEFAULT '',
                        `deductValuesJson`      TEXT NOT NULL DEFAULT '',
                        `createdAt`             INTEGER NOT NULL,
                        `updatedAt`             INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_segment_pci_session ON segment_pci(sessionId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_segment_pci_unique ON segment_pci(sessionId, segmentIndex)")

                // 3. Tabel pci_distress_items
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pci_distress_items` (
                        `id`            INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `segmentId`     INTEGER NOT NULL,
                        `sessionId`     INTEGER NOT NULL,
                        `type`          TEXT NOT NULL,
                        `severity`      TEXT NOT NULL,
                        `quantity`      REAL NOT NULL,
                        `notes`         TEXT NOT NULL DEFAULT '',
                        `density`       REAL NOT NULL DEFAULT 0.0,
                        `deductValue`   REAL NOT NULL DEFAULT 0.0,
                        `photoPath`     TEXT NOT NULL DEFAULT '',
                        `audioPath`     TEXT NOT NULL DEFAULT '',
                        `gpsLat`        REAL NOT NULL DEFAULT 0.0,
                        `gpsLng`        REAL NOT NULL DEFAULT 0.0,
                        `sta`           TEXT NOT NULL DEFAULT '',
                        `createdAt`     INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_pci_distress_segment ON pci_distress_items(segmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_pci_distress_session ON pci_distress_items(sessionId)")
            }
        }

        fun getInstance(context: Context): RoadSenseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RoadSenseDatabase::class.java,
                    "roadsense_database"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                        MIGRATION_7_8, MIGRATION_8_9               // ← baru
                    )
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}