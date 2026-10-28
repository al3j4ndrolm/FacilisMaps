package io.github.al3j4ndrolm.facilis.maps.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.al3j4ndrolm.facilis.maps.MapStyle
import io.github.al3j4ndrolm.facilis.maps.MapView
import io.github.al3j4ndrolm.facilis.maps.Marker
import io.github.al3j4ndrolm.facilis.maps.Point
import io.github.al3j4ndrolm.facilis.maps.Shape

/**
 * A map with no setup: no API key, no lifecycle code.
 *
 * ```
 * Map(center = sjsu, markers = listOf(Marker(sjsu, "MQH 225")), onMarkerTap = { })
 * ```
 *
 * @param center where the map looks. Changing it moves the map there; leave it null to fit all
 *   [markers] and [shapes] (or the user's location with [showMyLocation]).
 * @param zoom 0 shows the whole world, 15 a neighborhood, 20 a building.
 * @param showMyLocation shows the user's location as a blue dot. Asks for the permission the first time.
 * @param onMoved runs with the new center each time the user stops moving the map.
 */
@Composable
fun Map(
    modifier: Modifier = Modifier,
    center: Point? = null,
    zoom: Double? = null,
    markers: List<Marker> = emptyList(),
    shapes: List<Shape> = emptyList(),
    showMyLocation: Boolean = false,
    style: MapStyle = MapStyle.Auto,
    onTap: ((Point) -> Unit)? = null,
    onLongPress: ((Point) -> Unit)? = null,
    onMarkerTap: ((Marker) -> Unit)? = null,
    onMoved: ((Point) -> Unit)? = null,
) {
    // Only move the camera when center or zoom change, so recomposing doesn't undo the user's panning.
    val applied = remember { arrayOfNulls<Any>(2) }
    val parent = rememberCompositionContext()
    val icons = remember { arrayOfNulls<ContentIcons>(1) }
    AndroidView(
        modifier = modifier,
        factory = { MapView(it).also { view -> icons[0] = ContentIcons(view, parent) } },
        update = { view ->
            val contentIcons = icons[0]!!
            contentIcons.show(markers)
            view.shapes = shapes
            view.showMyLocation = showMyLocation
            view.mapStyle = style
            view.onTap = onTap
            view.onLongPress = onLongPress
            view.onMarkerTap = onMarkerTap?.let { tap -> { tapped -> tap(contentIcons.original(tapped)) } }
            view.onMoved = onMoved
            if (center != null && (center != applied[0] || zoom != applied[1])) {
                applied[0] = center
                applied[1] = zoom
                view.moveTo(center, zoom)
            }
        },
        onRelease = {
            icons[0]?.release()
            it.destroy()
        },
    )
}
