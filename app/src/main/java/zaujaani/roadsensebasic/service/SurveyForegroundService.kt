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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
 * SurveyForegroundService v2.1
 *
 * FIX dari v2:
 *
 * FIX #8 — Sync engine state saat pause/resume dari notification shade
 *   Sebelumnya: ACTION_PAUSE/RESUME dari notifikasi hanya stop/start GPS collection
 *   tapi tidak memanggil surveyEngine.pauseSurvey()/resumeSurvey().
 *   Akibatnya: engine.sessionState tetap SURVEYING saat GPS sudah berhenti,
 *   menyebabkan state mismatch antara engine dan service.
 *
 *   FIX: Tambahkan serviceScope.launch { surveyEngine.pauseSurvey() / resumeSurvey() }
 *   di ACTION_PAUSE dan ACTION_RESUME handler.
 *
 * Fix sebelumnya dari v2 tetap ada (WakeLock, Pause/Resume UI, etc).
 */
@AndroidEntryPoint
class SurveyForegroundService : android.app.Service() {

    @Inject lateinit var surveyEngine: SurveyEngine
    @Inject lateinit var gpsGateway: GPSGateway
    @Inject lateinit var sensorGateway: SensorGateway

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var gpsJob: Job? = null
    private var wakeLockRenewJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var isManuallyStopped = false
    private var isPausedState = false
    private var lastNotificationUpdate = 0L
    private var lastKnownSpeedKmh = 0f

    companion object {
        private const val WAKE_LOCK_SINGLE_MS = 90 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS  = 85 * 60 * 1000L
        private const val NOTIF_THROTTLE_MS   = 3_000L
    }

    // ══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        startWakeLockRenewal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            Constants.ACTION_START -> {
                isManuallyStopped = false
                isPausedState     = false
                startForeground(
                    Constants.NOTIFICATION_ID,
                    buildNotification(getString(R.string.survey_running), paused = false)
                )
                startCollecting()
            }

            Constants.ACTION_PAUSE -> {
                isPausedState = true
                stopCollecting()
                updateNotification(getString(R.string.survey_paused), paused = true)

                // FIX #8: sync engine state — sebelumnya ini HILANG
                serviceScope.launch {
                    try {
                        surveyEngine.pauseSurvey()
                        Timber.d("Engine paused from notification")
                    } catch (e: Exception) {
                        Timber.e(e, "Error pausing engine from notification")
                    }
                }
            }

            Constants.ACTION_RESUME -> {
                isPausedState = false
                startCollecting()
                updateNotification(getString(R.string.survey_running), paused = false)

                // FIX #8: sync engine state — sebelumnya ini HILANG
                serviceScope.launch {
                    try {
                        surveyEngine.resumeSurvey()
                        Timber.d("Engine resumed from notification")
                    } catch (e: Exception) {
                        Timber.e(e, "Error resuming engine from notification")
                    }
                }
            }

            Constants.ACTION_STOP -> {
                isManuallyStopped = true
                isPausedState     = false
                stopCollecting()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Flush telemetry sebelum cancel scope
        val flushScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        flushScope.launch {
            try {
                surveyEngine.flushTelemetry()
                Timber.d("Telemetry flushed on destroy")
            } catch (e: Exception) {
                Timber.e(e, "Error flushing telemetry on destroy")
            } finally {
                flushScope.cancel()
            }
        }

        stopCollecting()
        wakeLockRenewJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isManuallyStopped) {
            Timber.d("Task removed, restarting service (paused=$isPausedState)")
            val restartIntent = Intent(applicationContext, SurveyForegroundService::class.java).apply {
                action = if (isPausedState) Constants.ACTION_PAUSE else Constants.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        } else {
            Timber.d("Task removed, survey manually stopped — no restart")
        }
        super.onTaskRemoved(rootIntent)
    }

    // ══════════════════════════════════════════════════════════════════
    // GPS COLLECTION
    // ══════════════════════════════════════════════════════════════════

    private fun startCollecting() {
        if (gpsJob?.isActive == true) return
        sensorGateway.startListening()

        gpsJob = gpsGateway.getLocationFlow()
            .catch { e -> Timber.e(e, "GPS flow error") }
            .onEach { location ->
                // GPS accuracy filter: buang titik buruk
                if (location.accuracy > Constants.GPS_ACCURACY_THRESHOLD) {
                    Timber.v("GPS point discarded: accuracy ${location.accuracy}m > threshold")
                    return@onEach
                }

                surveyEngine.updateLocation(location.toLocationData())
                lastKnownSpeedKmh = location.speed * 3.6f

                val now = System.currentTimeMillis()
                if (now - lastNotificationUpdate > NOTIF_THROTTLE_MS) {
                    refreshNotification()
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

    // ══════════════════════════════════════════════════════════════════
    // WAKE LOCK
    // ══════════════════════════════════════════════════════════════════

    private fun acquireWakeLock() {
        val pm = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RoadSense::SurveyWakeLock"
        ).apply {
            acquire(WAKE_LOCK_SINGLE_MS)
            Timber.d("WakeLock acquired (${WAKE_LOCK_SINGLE_MS / 60_000} min)")
        }
    }

    private fun startWakeLockRenewal() {
        wakeLockRenewJob = serviceScope.launch {
            while (true) {
                delay(WAKE_LOCK_RENEW_MS)
                try {
                    wakeLock?.let { wl ->
                        if (wl.isHeld) {
                            wl.release()
                            Timber.d("WakeLock released for renewal")
                        }
                    }
                    acquireWakeLock()
                    Timber.d("WakeLock renewed")
                } catch (e: Exception) {
                    Timber.e(e, "Error renewing WakeLock")
                }
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Timber.e(e, "Error releasing WakeLock")
        } finally {
            wakeLock = null
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ══════════════════════════════════════════════════════════════════

    private fun refreshNotification() {
        val dist = surveyEngine.getCurrentDistance()
        val distText = if (dist < 1000) {
            getString(R.string.distance_m_format, dist.toInt())
        } else {
            getString(R.string.distance_km_format, dist / 1000.0)
        }
        val speedText = if (lastKnownSpeedKmh > 0.5f) {
            "${lastKnownSpeedKmh.toInt()} km/h  •  $distText"
        } else {
            distText
        }
        updateNotification(speedText, paused = false)
    }

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
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String, paused: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SurveyForegroundService::class.java).apply { action = Constants.ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SurveyForegroundService::class.java).apply {
                action = if (paused) Constants.ACTION_RESUME else Constants.ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleLabel = if (paused) getString(R.string.resume) else getString(R.string.action_pause)
        val toggleIcon  = if (paused) R.drawable.ic_play else R.drawable.ic_pause

        val title = if (paused) {
            "${getString(R.string.notification_title)} — ${getString(R.string.survey_paused)}"
        } else {
            getString(R.string.notification_title)
        }

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(toggleIcon, toggleLabel, toggleIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String, paused: Boolean) {
        val notification = buildNotification(text, paused)
        getSystemService(NotificationManager::class.java)?.notify(Constants.NOTIFICATION_ID, notification)
    }

    private fun Location.toLocationData(): LocationData = LocationData(
        latitude  = latitude,
        longitude = longitude,
        altitude  = altitude,
        speed     = speed,
        accuracy  = accuracy
    )
}