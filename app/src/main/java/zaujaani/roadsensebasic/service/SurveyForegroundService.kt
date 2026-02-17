package zaujaani.roadsensebasic.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.domain.engine.SurveyEngine
import zaujaani.roadsensebasic.gateway.GPSGateway
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.util.Constants
import javax.inject.Inject

@AndroidEntryPoint
class SurveyForegroundService : Service() {

    @Inject
    lateinit var surveyEngine: SurveyEngine

    @Inject
    lateinit var gpsGateway: GPSGateway

    @Inject
    lateinit var sensorGateway: SensorGateway

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var gpsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Jangan startForeground di sini, pindah ke ACTION_START
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_START -> {
                startForeground(Constants.NOTIFICATION_ID, createNotification("Survey is running"))
                startCollecting()
            }
            Constants.ACTION_STOP -> {
                stopCollecting()
                stopSelf()
            }
            Constants.ACTION_PAUSE -> {
                stopCollecting()
                updateNotification("Survey paused")
            }
        }
        return START_STICKY
    }

    private fun startCollecting() {
        if (gpsJob != null) return
        gpsJob = gpsGateway.getLocationFlow()
            .onEach { location ->
                surveyEngine.updateLocation(location)
            }
            .launchIn(serviceScope)
        sensorGateway.startListening()
    }

    private fun stopCollecting() {
        gpsJob?.cancel()
        gpsJob = null
        sensorGateway.stopListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Simpan data yang tersisa
        surveyEngine.flushTelemetry()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                Constants.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows survey status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RoadSense Survey")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(Constants.NOTIFICATION_ID, notification)
    }
}