package io.github.al3j4ndrolm.facilis.maps.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.al3j4ndrolm.facilis.maps.steps

/**
 * The steps taken since this first composed. It keeps counting while the screen is off, and stops
 * when it leaves the composition. Asks for the physical activity permission itself.
 *
 * ```
 * val taken = rememberSteps()
 * Text("$taken steps")
 * ```
 *
 * @return 0 until the first step, and stays 0 if the permission was denied or the phone can't count steps.
 */
@Composable
fun rememberSteps(): Int {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val taken by produceState(initialValue = 0, lifecycle) {
        lifecycle.awaitResumed()
        steps.watch().collect { value = it }
    }
    return taken
}
