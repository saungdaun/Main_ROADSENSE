package zaujaani.roadsensebasic.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import zaujaani.roadsensebasic.data.local.RoadSenseDatabase
import zaujaani.roadsensebasic.data.local.dao.PhotoAnalysisDao
import zaujaani.roadsensebasic.data.repository.PhotoAnalysisRepository
import zaujaani.roadsensebasic.data.repository.SurveyRepository
import zaujaani.roadsensebasic.data.repository.TelemetryRepository
import zaujaani.roadsensebasic.domain.engine.*
import zaujaani.roadsensebasic.gateway.BluetoothGateway
import zaujaani.roadsensebasic.gateway.GPSGateway
import zaujaani.roadsensebasic.gateway.SensorGateway
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RoadSenseDatabase {
        return RoadSenseDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideTelemetryRepository(db: RoadSenseDatabase): TelemetryRepository {
        return TelemetryRepository(db)
    }

    @Provides
    @Singleton
    fun provideSurveyRepository(db: RoadSenseDatabase): SurveyRepository {
        return SurveyRepository(db)
    }

    @Provides
    @Singleton
    fun provideGPSGateway(@ApplicationContext context: Context): GPSGateway {
        return GPSGateway(context)
    }

    @Provides
    @Singleton
    fun provideSensorGateway(@ApplicationContext context: Context): SensorGateway {
        return SensorGateway(context)
    }

    @Provides
    @Singleton
    fun provideBluetoothGateway(
        @ApplicationContext context: Context,
        sensorGateway: SensorGateway
    ): BluetoothGateway {
        return BluetoothGateway(context, sensorGateway)
    }

    @Provides
    @Singleton
    fun provideVibrationAnalyzer(): VibrationAnalyzer {
        return VibrationAnalyzer()
    }

    @Provides
    @Singleton
    fun provideConfidenceCalculator(): ConfidenceCalculator {
        return ConfidenceCalculator()
    }

    @Provides
    @Singleton
    fun provideSDICalculator(): SDICalculator {
        return SDICalculator()
    }

    @Provides
    @Singleton
    fun provideSurveyEngine(
        sensorGateway: SensorGateway,
        telemetryRepository: TelemetryRepository,
        surveyRepository: SurveyRepository,
        vibrationAnalyzer: VibrationAnalyzer,
        confidenceCalculator: ConfidenceCalculator,
        sdiCalculator: SDICalculator
    ): SurveyEngine {
        return SurveyEngine(
            sensorGateway = sensorGateway,
            telemetryRepository = telemetryRepository,
            surveyRepository = surveyRepository,
            vibrationAnalyzer = vibrationAnalyzer,
            confidenceCalculator = confidenceCalculator,
            sdiCalculator = sdiCalculator
        )
    }
    @Provides
    @Singleton
    fun providePhotoAnalysisDao(db: RoadSenseDatabase): PhotoAnalysisDao {
        return db.photoAnalysisDao()
    }

    // Repository analisis AI — akan dipakai oleh PhotoAnalysisViewModel
    @Provides
    @Singleton
    fun providePhotoAnalysisRepository(
        dao: PhotoAnalysisDao,
        @ApplicationContext context: Context
    ): PhotoAnalysisRepository {
        return PhotoAnalysisRepository(dao, context)
    }
}
