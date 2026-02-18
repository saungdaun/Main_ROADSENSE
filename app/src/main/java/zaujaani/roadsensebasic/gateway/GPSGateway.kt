package zaujaani.roadsensebasic.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPSGateway — wraps FusedLocationProviderClient as a Flow.
 *
 * MIUI note: On MIUI/HyperOS, background location can be throttled.
 * The foreground service + PRIORITY_HIGH_ACCURACY keeps GPS alive.
 * Interval is set to 1000 ms, minUpdate 500 ms — suitable for road survey at up to 120 km/h.
 */
@Singleton
class GPSGateway @Inject constructor(
    private val context: Context
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private fun createLocationRequest(intervalMs: Long = 1000L): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMinUpdateDistanceMeters(0f)          // no distance filter — survey needs every point
            .setWaitForAccurateLocation(false)        // don't block startup for accuracy
            .build()

    /** Continuous location flow — intended for use from ForegroundService and ViewModel. */
    fun getLocationFlow(intervalMs: Long = 1000L): Flow<Location> = callbackFlow {
        if (!hasLocationPermission()) {
            Timber.w("GPSGateway: location permission missing, flow will emit nothing")
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                createLocationRequest(intervalMs),
                callback,
                Looper.getMainLooper()
            ).addOnFailureListener { e ->
                Timber.e(e, "GPSGateway: failed to request location updates")
                close(e)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "GPSGateway: SecurityException requesting updates")
            close(e)
        }

        awaitClose {
            try {
                fusedLocationClient.removeLocationUpdates(callback)
                Timber.d("GPSGateway: location updates removed")
            } catch (e: Exception) {
                Timber.w(e, "GPSGateway: error removing location updates")
            }
        }
    }

    /** One-shot last known location — may be null or stale. */
    @androidx.annotation.RequiresPermission(
        anyOf = [
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ]
    )
    suspend fun getLastKnownLocation(): Location? {//Function "getLastKnownLocation" is never used
        if (!hasLocationPermission()) return null
        return try {

            fusedLocationClient.lastLocation.await()//Call requires permission which may be rejected by user: code should explicitly check to see if permission is available (with `checkPermission`) or explicitly handle a potential `SecurityException`
        } catch (e: Exception) {
            Timber.w(e, "GPSGateway: failed to get last known location")
            null
        }
    }

    /** Returns true if the device GPS provider is enabled. */
    fun isGpsProviderEnabled(): Boolean {//Function "isGpsProviderEnabled" is never used
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }
}