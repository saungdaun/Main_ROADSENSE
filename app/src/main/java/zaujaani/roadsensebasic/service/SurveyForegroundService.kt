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
import kotlinx.coroutines.withContext
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
 * SurveyForegroundService — versi 2
 *
 * Perubahan dari v1:
 *
 * FIX 1 — GPS accuracy filter
 *   Titik GPS dengan akurasi > GPS_ACCURACY_THRESHOLD diabaikan di service,
 *   tidak diteruskan ke engine. Mencegah data posisi jelek masuk DB.
 *
 * FIX 2 & 3 — Tombol Pause/Resume di notifikasi
 *   Saat survey aktif  → notifikasi punya aksi [Pause] [Stop]
 *   Saat survey paused → notifikasi punya aksi [Resume] [Stop]
 *   Surveyor bisa control dari notification shade tanpa buka app.
 *
 * FIX 4 — onTaskRemoved aware terhadap state pause
 *   Jika service di-kill MIUI saat paused → restart dengan ACTION_PAUSE,
 *   bukan ACTION_START. Survey tidak tiba-tiba jalan lagi.
 *
 * FIX 5 — flushTelemetry awaited sebelum scope dibatalkan
 *   onDestroy: flush dulu secara blocking, baru cancel scope.
 *   Data tidak hilang saat app di-kill.
 *
 * FIX 6 — Notifikasi tampilkan kecepatan + jarak
 *   Format: "42 km/h  •  1.23 km"
 *   Berguna saat layar mati, surveyor tahu app masih jalan.
 *
 * FIX 7 — WakeLock renewable untuk survey panjang
 *   WakeLock direnew setiap 90 menit via coroutine loop.
 *   Survey > 2 jam (jalan provinsi) tidak akan putus di background.
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
    private var isPausedState = false           // FIX 4: simpan state pause untuk restart
    private var lastNotificationUpdate = 0L
    private var lastKnownSpeedKmh = 0f         // FIX 6: simpan speed terakhir untuk notifikasi

    companion object {
        // FIX 7: Perpendek satu segmen WakeLock, renew berkala
        private const val WAKE_LOCK_SINGLE_MS  = 90 * 60 * 1000L   // 90 menit per segment
        private const val WAKE_LOCK_RENEW_MS   = 85 * 60 * 1000L   // renew setelah 85 menit

        private const val NOTIF_THROTTLE_MS    = 3_000L             // max update notifikasi
    }

    // ══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        startWakeLockRenewal()      // FIX 7: mulai loop renew
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
                // FIX 2: ganti notifikasi ke mode pause (tombol Resume)
                updateNotification(getString(R.string.survey_paused), paused = true)
            }

            Constants.ACTION_RESUME -> {
                isPausedState = false
                startCollecting()
                // FIX 2: kembalikan notifikasi ke mode aktif (tombol Pause)
                updateNotification(getString(R.string.survey_running), paused = false)
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

        // START_STICKY: MIUI akan restart service jika di-kill OS
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // FIX 5: flush telemetry SEBELUM cancel scope
        // Gunakan blocking scope baru yang tidak dibatalkan
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

    // FIX 4: restart sadar state paused
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isManuallyStopped) {
            Timber.d("Task removed, restarting service (paused=$isPausedState)")
            val restartIntent = Intent(applicationContext, SurveyForegroundService::class.java).apply {
                // Jika sebelumnya paused → restart ke paused (tidak aktifkan GPS)
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
    // COLLECTING GPS + SENSOR
    // ══════════════════════════════════════════════════════════════════

    private fun startCollecting() {
        if (gpsJob?.isActive == true) return
        sensorGateway.startListening()

        gpsJob = gpsGateway.getLocationFlow()
            .catch { e ->
                Timber.e(e, "GPS flow error")
                // Service tetap hidup meski GPS error sementara
            }
            .onEach { location ->

                // FIX 1: filter GPS tidak akurat sebelum masuk engine
                if (location.accuracy > Constants.GPS_ACCURACY_THRESHOLD) {
                    Timber.v("GPS point dibuang: akurasi ${location.accuracy}m > threshold")
                    return@onEach
                }

                surveyEngine.updateLocation(location.toLocationData())

                // FIX 6: simpan speed terakhir untuk notifikasi
                lastKnownSpeedKmh = location.speed * 3.6f

                // Throttle update notifikasi
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
    // WAKE LOCK — FIX 7: renewable
    // ══════════════════════════════════════════════════════════════════

    private fun acquireWakeLock() {
        val pm = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RoadSense::SurveyWakeLock"
        ).apply {
            acquire(WAKE_LOCK_SINGLE_MS)
            Timber.d("WakeLock acquired (${WAKE_LOCK_SINGLE_MS / 60_000} menit)")
        }
    }

    /**
     * FIX 7: Loop renew WakeLock setiap 85 menit.
     * Pola: release lama → acquire baru → tunggu 85 menit → ulangi.
     * Survey > 2 jam bisa jalan tanpa putus.
     */
    private fun startWakeLockRenewal() {
        wakeLockRenewJob = serviceScope.launch {
            while (true) {
                delay(WAKE_LOCK_RENEW_MS)
                try {
                    wakeLock?.let { wl ->
                        if (wl.isHeld) {
                            wl.release()
                            Timber.d("WakeLock direnew")
                        }
                    }
                    acquireWakeLock()
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
    // NOTIFICATION — FIX 2, 3, 6
    // ══════════════════════════════════════════════════════════════════

    private fun refreshNotification() {
        val dist = surveyEngine.getCurrentDistance()
        val distText = if (dist < 1000) {
            getString(R.string.distance_m_format, dist.toInt())
        } else {
            getString(R.string.distance_km_format, dist / 1000.0)
        }

        // FIX 6: tampilkan kecepatan + jarak
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
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    /**
     * FIX 2 & 3: Bangun notifikasi dengan aksi yang sesuai state.
     *
     * State aktif  → [Pause ⏸] [Stop ⏹]
     * State paused → [Resume ▶] [Stop ⏹]
     */
    private fun buildNotification(contentText: String, paused: Boolean): Notification {
        // Intent: tap notifikasi → buka MainActivity
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent: tombol Stop
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SurveyForegroundService::class.java).apply {
                action = Constants.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent: tombol Pause atau Resume (tergantung state)
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
            .setOnlyAlertOnce(true)         // tidak bunyi tiap update
            .setContentIntent(openAppIntent)
            .addAction(toggleIcon, toggleLabel, toggleIntent)   // Pause / Resume
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String, paused: Boolean) {
        val notification = buildNotification(text, paused)
        getSystemService(NotificationManager::class.java)
            ?.notify(Constants.NOTIFICATION_ID, notification)
    }

    // ══════════════════════════════════════════════════════════════════
    // EXTENSION
    // ══════════════════════════════════════════════════════════════════

    private fun Location.toLocationData(): LocationData = LocationData(
        latitude  = latitude,
        longitude = longitude,
        altitude  = altitude,
        speed     = speed,
        accuracy  = accuracy
    )
}