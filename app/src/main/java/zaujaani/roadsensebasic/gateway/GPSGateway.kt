package zaujaani.roadsensebasic.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import zaujaani.roadsensebasic.util.sensor.GpsConfidenceFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPSGateway v2.1 — FusedLocationProvider sebagai Flow.
 *
 * PERBAIKAN vs v2:
 *
 * FIX #GPS-1 — Filter warm-up menyebabkan garis muncul telat (utama)
 *   Root cause: `gpsConfidenceFilter.reset()` dipanggil di `awaitClose`.
 *   Setiap kali GPS flow baru dimulai (survey baru / balik dari layar lain),
 *   filter mulai cold lagi → butuh N titik untuk warm-up → polyline telat muncul.
 *   FIX: Hapus `gpsConfidenceFilter.reset()` dari awaitClose.
 *        Tambahkan warmup bypass: 5 titik pertama SELALU lolos konfiden filter.
 *
 * FIX #GPS-2 — Interval GPS terlalu lambat (1000ms → 500ms)
 *   Di 60 km/h, 1 detik = 16.7m antar titik → garis kasar/patah-patah.
 *   Dengan 500ms → max 8.3m antar titik → garis lebih halus.
 *
 * FIX #GPS-3 — Near-duplicate threshold 0.5m terlalu ketat saat berjalan pelan
 *   Diubah ke 0.2m. Cukup untuk hindari titik duplikat saat berhenti,
 *   tapi tidak membuang titik valid saat kendaraan berjalan pelan (< 5 km/h).
 */
@Singleton
class GPSGateway @Inject constructor(
    private val context: Context,
    private val gpsConfidenceFilter: GpsConfidenceFilter
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    companion object {
        // FIX #GPS-2: 500ms = titik lebih rapat, garis lebih halus
        private const val DEFAULT_INTERVAL_MS = 500L

        // Bypass confidence filter untuk N titik pertama saat flow baru dimulai
        private const val WARMUP_BYPASS_COUNT = 5

        // FIX #GPS-3: lebih lenient agar kendaraan pelan tidak drop titik
        private const val MIN_DISTANCE_THRESHOLD = 0.2f
    }

    private fun createLocationRequest(intervalMs: Long = DEFAULT_INTERVAL_MS): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMinUpdateDistanceMeters(0f)           // jangan batasi jarak — engine yang filter
            .setWaitForAccurateLocation(false)         // jangan tunggu akurasi bagus → responsif
            .build()

    /**
     * Continuous location flow — digunakan oleh ForegroundService (engine) DAN
     * MapViewModel (UI / polyline).
     *
     * Filter yang diterapkan di sini:
     * 1. Warmup bypass: 5 titik pertama SELALU lolos (hindari delay awal)
     * 2. GpsConfidenceFilter: titik noisy ditolak SETELAH warmup
     * 3. Near-duplicate: titik yang sama persis (< 0.2m) tidak di-emit
     *
     * Filter accuracy (> 40m) dilakukan di SurveyForegroundService bukan di sini,
     * agar MapFragment tetap bisa update posisi marker walaupun akurasi kurang bagus.
     */
    fun getLocationFlow(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<Location> = callbackFlow {

        if (!hasLocationPermission()) {
            Timber.w("GPSGateway: permission missing")
            awaitClose { }
            return@callbackFlow
        }

        var lastEmittedLocation: Location? = null
        // FIX #GPS-1: warmup counter — bypass confidence filter untuk N titik pertama
        var warmupRemaining = WARMUP_BYPASS_COUNT

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->

                    // FIX #GPS-1: warmup bypass — jangan reject titik pertama
                    val isWarmup = warmupRemaining > 0
                    if (isWarmup) {
                        warmupRemaining--
                    } else {
                        // Setelah warmup: cek confidence filter
                        if (!gpsConfidenceFilter.isValid(location)) {
                            Timber.d(
                                "GPS filtered (post-warmup): acc=${location.accuracy}, " +
                                        "speed=${if (location.hasSpeed()) "%.1f".format(location.speed) else "n/a"}"
                            )
                            return
                        }
                    }

                    // FIX #GPS-3: Near-duplicate filter (lebih lenient: 0.2m vs 0.5m)
                    lastEmittedLocation?.let { prev ->
                        if (prev.distanceTo(location) < MIN_DISTANCE_THRESHOLD) {
                            Timber.v("GPS duplicate dropped (distance < ${MIN_DISTANCE_THRESHOLD}m)")
                            return
                        }
                    }

                    val result = trySend(location)
                    if (result.isSuccess) {
                        lastEmittedLocation = location
                    } else {
                        Timber.w("GPS dropped: collector busy")
                    }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                createLocationRequest(intervalMs),
                callback,
                Looper.myLooper() ?: Looper.getMainLooper()
            ).addOnFailureListener { e ->
                Timber.e(e, "GPSGateway: failed to request updates")
                close(e)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "GPSGateway: SecurityException")
            close(e)
        }

        awaitClose {
            try {
                fusedLocationClient.removeLocationUpdates(callback)
                // FIX #GPS-1: JANGAN reset filter di sini!
                // Reset menyebabkan filter cold setiap kali flow baru dimulai.
                // Filter sengaja dibiarkan warm agar survey berikutnya langsung responsif.
                // gpsConfidenceFilter.reset() ← DIHAPUS
                Timber.d("GPSGateway: location updates removed (filter kept warm)")
            } catch (e: Exception) {
                Timber.w(e, "GPSGateway: error on close")
            }
        }
    }

    /**
     * One-shot last known location.
     * Untuk inisialisasi awal peta, bukan tracking.
     */
    @RequiresPermission(
        anyOf = [
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ]
    )
    suspend fun getLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        return try {
            fusedLocationClient.lastLocation.await()
        } catch (e: Exception) {
            Timber.w(e, "GPSGateway: failed to get last location")
            null
        }
    }

    fun isGpsProviderEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }
}