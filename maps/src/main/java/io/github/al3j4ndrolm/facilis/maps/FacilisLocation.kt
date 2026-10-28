package io.github.al3j4ndrolm.facilis.maps

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import io.github.al3j4ndrolm.facilis.permission.Permission
import io.github.al3j4ndrolm.facilis.permission.permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The device's location and street addresses, with no setup. Every call asks for the location
 * permission itself the first time.
 *
 * ```
 * val here = location.current()
 * location.address(here)      // "1 Washington Sq, San Jose, CA"
 * ```
 */
val location: FacilisLocation get() = FacilisLocation

object FacilisLocation {

    private val scope = MainScope()
    private val context: Context get() = Tracker.appContext

    /**
     * Where the device is now. Asks for the permission if needed.
     *
     * @return null if the permission was denied, location is off, or there was no fix within [timeout].
     */
    suspend fun current(timeout: Duration = 15.seconds): Point? {
        if (!requestPermission()) return null
        return withTimeoutOrNull(timeout) { if (hasPlayServices()) fusedCurrent() else platformCurrent() }
            ?.toPoint()
            ?: run { warn("No location fix. Is location turned on?"); null }
    }

    /** Callback version of [current], for code without coroutines. */
    fun current(onResult: (Point?) -> Unit) {
        scope.launch { onResult(current()) }
    }

    /** The last known location: instant, but may be a few minutes old. Asks for the permission if needed. */
    suspend fun last(): Point? {
        if (!requestPermission()) return null
        return if (hasPlayServices()) {
            LocationServices.getFusedLocationProviderClient(context).lastLocation.awaitOrNull()?.toPoint()
        } else {
            platformLast()?.toPoint()
        }
    }

    /**
     * Live location updates about every [every], while something collects this. Asks for the
     * permission if needed and ends right away if it's denied.
     *
     * ```
     * location.watch().collect { here -> }
     * ```
     */
    @SuppressLint("MissingPermission") // asked just above
    fun watch(every: Duration = 5.seconds): Flow<Point> = callbackFlow {
        if (!requestPermission()) {
            close()
            return@callbackFlow
        }
        val millis = every.inWholeMilliseconds
        if (hasPlayServices()) {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { trySend(it.toPoint()) }
                }
            }
            client.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, millis).build(),
                callback,
                Looper.getMainLooper(),
            )
            awaitClose { client.removeLocationUpdates(callback) }
        } else {
            val manager = locationManager()
            val listener = LocationListenerCompat { trySend(it.toPoint()) }
            LocationManagerCompat.requestLocationUpdates(
                manager, platformProvider(), LocationRequestCompat.Builder(millis).build(), listener, Looper.getMainLooper(),
            )
            awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
        }
    }.conflate()

    /** The street address at [point], like "1 Washington Sq, San Jose, CA 95192, USA". Null if none was found. */
    suspend fun address(point: Point): String? =
        geocode { geocoder, onResult ->
            if (Build.VERSION.SDK_INT >= 33) {
                geocoder.getFromLocation(point.lat, point.lng, 1, listener(onResult))
            } else {
                @Suppress("DEPRECATION")
                onResult(geocoder.getFromLocation(point.lat, point.lng, 1))
            }
        }?.getAddressLine(0)

    /** Where [address] is, e.g. `location.find("1 Washington Sq, San Jose")`. Null if it wasn't found. */
    suspend fun find(address: String): Point? =
        geocode { geocoder, onResult ->
            if (Build.VERSION.SDK_INT >= 33) {
                geocoder.getFromLocationName(address, 1, listener(onResult))
            } else {
                @Suppress("DEPRECATION")
                onResult(geocoder.getFromLocationName(address, 1))
            }
        }?.takeIf { it.hasLatitude() && it.hasLongitude() }?.let { Point(it.latitude, it.longitude) }

    /** Whether the location permission is granted (precise or approximate). Doesn't ask. */
    val allowed: Boolean get() = permissions.check(Permission.Location)

    /** Asks for the location permission if it isn't granted yet. Returns whether it is now. */
    suspend fun requestPermission(): Boolean =
        allowed || permissions.request(Permission.Location).also { if (!it) warn("Location permission denied.") }

    // region Internal

    private fun hasPlayServices(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    @SuppressLint("MissingPermission") // checked by the callers
    private suspend fun fusedCurrent(): Location? {
        val cancel = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30_000)
            .build()
        return try {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(request, cancel.token)
                .awaitOrNull()
        } finally {
            cancel.cancel()
        }
    }

    @SuppressLint("MissingPermission") // checked by the callers
    private suspend fun platformCurrent(): Location? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        LocationManagerCompat.getCurrentLocation(locationManager(), platformProvider(), signal, mainExecutor()) {
            if (continuation.isActive) continuation.resume(it)
        }
    }

    @SuppressLint("MissingPermission") // checked by the callers
    private fun platformLast(): Location? {
        val manager = locationManager()
        return manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }

    private fun locationManager() = context.getSystemService(LocationManager::class.java)

    /** GPS needs the precise permission; the network provider works with approximate only. */
    private fun platformProvider(): String {
        val precise = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val manager = locationManager()
        return if (precise && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else {
            LocationManager.NETWORK_PROVIDER
        }
    }

    private fun mainExecutor(): Executor = ContextCompat.getMainExecutor(context)

    /** Runs a Geocoder lookup off the main thread and returns its first result. */
    private suspend fun geocode(lookup: (Geocoder, (List<Address>?) -> Unit) -> Unit): Address? {
        if (!Geocoder.isPresent()) {
            warn("This device has no geocoder, so addresses can't be looked up.")
            return null
        }
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                try {
                    lookup(Geocoder(context)) { results -> if (continuation.isActive) continuation.resume(results?.firstOrNull()) }
                } catch (e: Exception) {
                    // IOException when offline, IllegalArgumentException for bad input.
                    warn("Address lookup failed.", e)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    @RequiresApi(33)
    private fun listener(onResult: (List<Address>?) -> Unit) = object : Geocoder.GeocodeListener {
        override fun onGeocode(addresses: MutableList<Address>) = onResult(addresses)
        override fun onError(errorMessage: String?) {
            warn("Address lookup failed: $errorMessage")
            onResult(null)
        }
    }

    // endregion
}

internal fun Location.toPoint() = Point(latitude, longitude)

private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (!task.isSuccessful) warn("Location request failed.", task.exception)
        continuation.resume(if (task.isSuccessful) task.result else null)
    }
}
