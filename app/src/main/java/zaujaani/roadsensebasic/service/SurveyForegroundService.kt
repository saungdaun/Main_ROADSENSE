package zaujaani.roadsensebasic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import zaujaani.roadsensebasic.R
import zaujaani.roadsensebasic.domain.engine.SurveyEngine
import zaujaani.roadsensebasic.domain.model.LocationData
import zaujaani.roadsensebasic.gateway.GPSGateway
import zaujaani.roadsensebasic.gateway.SensorGateway
import zaujaani.roadsensebasic.ui.main.MainActivity
import zaujaani.roadsensebasic.util.Constants
import javax.inject.Inject

/**
 * Foreground service untuk survey jalan.
 * Memastikan GPS dan Sensor tetap aktif meski layar terkunci atau app di background.
 * MIUI-friendly: WakeLock partial + priority notification yang tidak bisa di-kill.
 */
@AndroidEntryPoint
class SurveyForegroundService : android.app.Service() {

    @Inject lateinit var surveyEngine: SurveyEngine
    @Inject lateinit var gpsGateway: GPSGateway
    @Inject lateinit var sensorGateway: SensorGateway

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var gpsJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isManuallyStopped = false
    private var lastNotificationUpdate = 0L

    companion object {
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L // 2 jam
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_START -> {
                isManuallyStopped = false
                startForeground(Constants.NOTIFICATION_ID, createNotification(
                    getString(R.string.survey_running)
                ))
                startCollecting()
            }
            Constants.ACTION_STOP -> {
                isManuallyStopped = true
                stopCollecting()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            Constants.ACTION_PAUSE -> {
                stopCollecting()
                updateNotification(getString(R.string.survey_paused))
            }
            Constants.ACTION_RESUME -> {
                startCollecting()
                updateNotification(getString(R.string.survey_running))
            }
        }
        // START_STICKY agar MIUI restart service jika di-kill (kecuali manual stop)
        return START_STICKY
    }

    private fun startCollecting() {
        if (gpsJob?.isActive == true) return
        sensorGateway.startListening()
        gpsJob = gpsGateway.getLocationFlow()
            .catch { e ->
                Timber.e(e, "GPS error")
                // service tetap berjalan, tidak perlu crash
            }
            .onEach { location ->
                surveyEngine.updateLocation(location.toLocationData())
                // Throttle update notifikasi maksimal 3 detik sekali
                val now = System.currentTimeMillis()
                if (now - lastNotificationUpdate > 3000) {
                    val dist = surveyEngine.getCurrentDistance()
                    val distText = if (dist < 1000) {
                        getString(R.string.distance_m_format, dist.toInt())
                    } else {
                        getString(R.string.distance_km_format, dist / 1000.0)
                    }
                    updateNotification(getString(R.string.notification_survey_progress, distText))
                    lastNotificationUpdate = now
                }
            }
            .launchIn(serviceScope)
    }

    private fun stopCollecting() {
        gpsJob?.cancel()
        gpsJob = null
        sensorGateway.stopListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        surveyEngine.flushTelemetry()
        stopCollecting()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // Penting untuk MIUI: jika service di-kill, coba restart
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isManuallyStopped) {
            Timber.d("Task removed, restarting service")
            val restartIntent = Intent(applicationContext, SurveyForegroundService::class.java).apply {
                action = Constants.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        } else {
            Timber.d("Task removed but manually stopped, no restart")
        }
        super.onTaskRemoved(rootIntent)
    }

    // ========== WAKE LOCK ==========

    private fun acquireWakeLock() {
        val powerManager = ContextCompat.getSystemService(this, PowerManager::class.java)
        powerManager?.let {
            wakeLock = it.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RoadSense::SurveyWakeLock"
            ).also { wl ->
                // Gunakan timeout untuk safety, agar tidak selamanya jika lupa release
                wl.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    // ========== NOTIFICATION ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                Constants.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SurveyForegroundService::class.java).apply {
                action = Constants.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
        manager?.notify(Constants.NOTIFICATION_ID, notification)
    }

    // Extension function to convert Location to LocationData
    private fun Location.toLocationData(): LocationData = LocationData(
        latitude = this.latitude,
        longitude = this.longitude,
        altitude = this.altitude,
        speed = this.speed,
        accuracy = this.accuracy
    )
}