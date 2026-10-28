package io.github.al3j4ndrolm.facilis.maps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Maps with no API key, no account and no setup. Available everywhere (Activity, ViewModel):
 *
 * ```
 * maps.open(sjsu, label = "Class")
 * maps.directions(to = sjsu, by = Travel.Walking)
 * ```
 */
val maps: FacilisMaps get() = FacilisMaps

object FacilisMaps {

    /**
     * The map style used in light mode. Defaults to OpenFreeMap (free, no key). Change it once,
     * e.g. in `Application.onCreate`, to use another provider.
     */
    @JvmStatic
    var lightStyle: String = "https://tiles.openfreemap.org/styles/liberty"

    /** The map style used in dark mode. See [lightStyle]. */
    @JvmStatic
    var darkStyle: String = "https://tiles.openfreemap.org/styles/dark"

    // region Hand off to a maps app

    /** Shows [point] in the user's maps app, with an optional [label] on the pin. */
    fun open(point: Point, label: String? = null): Boolean =
        launch(geoUri(point, label)) || launch(searchUrl(point.query()))

    /** Searches for [place] in the user's maps app, e.g. `maps.open("Blue Bottle Coffee, San Jose")`. */
    fun open(place: String): Boolean = launch(geoUri(place)) || launch(searchUrl(place))

    /** Shows the route to [to] in Google Maps (the app if installed, otherwise the website). */
    fun directions(to: Point, by: Travel = Travel.Driving): Boolean = launch(directionsUrl(to.query(), by))

    /** Shows the route to the address [to]. */
    fun directions(to: String, by: Travel = Travel.Driving): Boolean = launch(directionsUrl(to, by))

    // endregion

    private fun launch(uri: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        val activity = Tracker.resumed
        val context: Context = activity ?: Tracker.appContext
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            warn("No app can open $uri")
            false
        }
    }
}
