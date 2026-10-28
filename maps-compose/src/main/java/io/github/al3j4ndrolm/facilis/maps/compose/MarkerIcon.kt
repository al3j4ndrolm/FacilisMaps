package io.github.al3j4ndrolm.facilis.maps.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.al3j4ndrolm.facilis.maps.MarkerIcon
import io.github.al3j4ndrolm.facilis.maps.Point
import io.github.al3j4ndrolm.facilis.maps.Marker as PlainMarker

/**
 * A marker drawn by your own composable, with its bottom middle on [point]:
 *
 * ```
 * Marker(here) { PhotoImage(photo, Modifier.size(40.dp).clip(CircleShape)) }
 * ```
 *
 * Make it anywhere, a ViewModel included: [Map] draws it, and draws it again whenever it changes
 * (e.g. once a photo has loaded). Give it a size, e.g. with `Modifier.size`. It's shown as a
 * picture, so taps go to the map's `onMarkerTap` rather than to buttons inside it.
 *
 * @param data comes back in `onMarkerTap`, so you can tell markers apart.
 */
fun Marker(point: Point, title: String? = null, data: Any? = null, content: @Composable () -> Unit): PlainMarker =
    PlainMarker(point, title, data = data, icon = ContentIcon(content))

/**
 * A marker icon drawn from your own composable, for `Marker(point, icon = ...)`. Usually
 * `Marker(point) { }` is simpler.
 *
 * It's drawn again whenever its content changes; [keys] are only needed for values the content
 * reads without Compose noticing.
 */
@Composable
fun rememberMarkerIcon(vararg keys: Any?, content: @Composable () -> Unit): MarkerIcon =
    remember(*keys) { ContentIcon(content) }

/** A marker icon drawn from a composable by [Map]. The same composable means the same icon. */
internal class ContentIcon(val content: @Composable () -> Unit) : MarkerIcon {
    override fun equals(other: Any?): Boolean = other is ContentIcon && other.content === content
    override fun hashCode(): Int = System.identityHashCode(content)
}
