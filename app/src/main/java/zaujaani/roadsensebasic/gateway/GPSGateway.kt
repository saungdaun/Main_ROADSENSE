package zaujaani.roadsensebasic.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class GPSGateway @Inject constructor(
    private val context: Context
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null

    private fun createLocationRequest(): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()
    }

    fun getLocationFlow(): Flow<Location> = callbackFlow {
        // Cek permission terlebih dahulu
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation && !hasCoarseLocation) {
            // Tidak punya izin, kembalikan flow kosong tanpa error
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    trySend(location)
                }
            }
        }
        locationCallback = callback

        fusedLocationClient.requestLocationUpdates(
            createLocationRequest(),
            callback,
            Looper.getMainLooper()
        ).addOnFailureListener { exception ->
            close(exception)
        }

        awaitClose {
            locationCallback?.let {
                try {
                    fusedLocationClient.removeLocationUpdates(it)
                } catch (e: SecurityException) {
                    // Abaikan, permission mungkin sudah dicabut
                }
                locationCallback = null
            }
        }
    }

    suspend fun getLastKnownLocation(): Location? {
        // Cek permission
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation && !hasCoarseLocation) {
            return null
        }

        return try {
            fusedLocationClient.lastLocation.await()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun isGPSEnabled(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
            continuation.resume(isGpsEnabled)
        }
    }

    suspend fun getCurrentAccuracy(): Float {
        return try {
            getLastKnownLocation()?.accuracy ?: 0f
        } catch (e: Exception) {
            0f
        }
    }
}