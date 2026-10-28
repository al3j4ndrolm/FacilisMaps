package io.github.al3j4ndrolm.facilis.maps.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.al3j4ndrolm.facilis.maps.Point
import io.github.al3j4ndrolm.facilis.maps.location
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where the device is, found once when this first composes. Asks for the location permission
 * itself.
 *
 * ```
 * val here = rememberLocation()
 * Map(center = here, showMyLocation = true)
 * ```
 *
 * @return null until it's found, and stays null if the permission was denied, location is off,
 *   or there was no fix within [timeout].
 */
@Composable
fun rememberLocation(timeout: Duration = 15.seconds): Point? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val here by produceState<Point?>(initialValue = null, lifecycle) {
        lifecycle.awaitResumed()
        value = location.current(timeout)
    }
    return here
}

/**
 * Waits until the screen is showing: the permission dialog can't appear before that (e.g. behind
 * the lock screen), and asking then would count as a denial.
 */
private suspend fun Lifecycle.awaitResumed() {
    currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
}

/**
 * Where the device is, updated about [every] while the screen is visible (it pauses in the
 * background, to save battery). Asks for the location permission itself, once.
 *
 * ```
 * val here = rememberLiveLocation()
 * Text("${here?.distanceTo(sjsu)} to class")
 * ```
 *
 * @return null until the first fix, and stays null if the permission was denied.
 */
@Composable
fun rememberLiveLocation(every: Duration = 5.seconds): Point? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val here by produceState<Point?>(initialValue = null, lifecycle, every) {
        // Ask once, outside the loop below, so a denial isn't asked again each time the screen comes back.
        lifecycle.awaitResumed()
        if (!location.requestPermission()) return@produceState
        if (value == null) value = location.last()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            location.watch(every).collect { value = it }
        }
    }
    return here
}
