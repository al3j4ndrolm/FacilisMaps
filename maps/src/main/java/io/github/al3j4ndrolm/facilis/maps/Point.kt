package io.github.al3j4ndrolm.facilis.maps

import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A spot on Earth, in degrees. Can be saved, e.g. with FacilisStorage. */
@Serializable
data class Point(val lat: Double, val lng: Double) {

    init {
        require(lat in -90.0..90.0) { "lat must be between -90 and 90, was $lat." }
        require(lng in -180.0..180.0) { "lng must be between -180 and 180, was $lng." }
    }

    /** Straight-line distance ("as the crow flies"), not along roads. */
    fun distanceTo(other: Point): Distance = Distance(metersBetween(this, other))

    /** The direction from here to [other], in degrees: 0 is north, 90 east, 180 south, 270 west. */
    fun bearingTo(other: Point): Double {
        val lat1 = Math.toRadians(lat)
        val lat2 = Math.toRadians(other.lat)
        val dLng = Math.toRadians(other.lng - lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    override fun toString(): String = String.format(Locale.US, "%.6f, %.6f", lat, lng)
}

/** A length on the ground. Write it as `500.meters` or `1.5.km`. */
@Serializable
@JvmInline
value class Distance(val meters: Double) : Comparable<Distance> {
    val kilometers: Double get() = meters / 1000
    val miles: Double get() = meters / METERS_PER_MILE

    override fun compareTo(other: Distance): Int = meters.compareTo(other.meters)

    /** "850 m", "1.2 km", "12 km". */
    override fun toString(): String = when {
        meters < 1000 -> "${meters.roundToInt()} m"
        meters < 10_000 -> String.format(Locale.US, "%.1f km", kilometers)
        else -> "${kilometers.roundToInt()} km"
    }
}

val Int.meters: Distance get() = Distance(toDouble())
val Double.meters: Distance get() = Distance(this)
val Int.km: Distance get() = Distance(toDouble() * 1000)
val Double.km: Distance get() = Distance(this * 1000)

/** How to get somewhere in [FacilisMaps.directions]. */
enum class Travel { Driving, Walking, Transit, Biking }

// region Pure logic (unit tested)

private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val METERS_PER_MILE = 1609.344

/** Haversine distance. */
internal fun metersBetween(a: Point, b: Point): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** How far apart two directions are, in degrees (0 to 180), going the short way around. */
internal fun angleBetween(a: Double, b: Double): Double {
    val d = ((b - a) % 360 + 360) % 360
    return if (d > 180) 360 - d else d
}

/** The point [meters] away from [from], heading [bearingDegrees] (0 = north, 90 = east). */
internal fun destination(from: Point, meters: Double, bearingDegrees: Double): Point {
    val angular = meters / EARTH_RADIUS_METERS
    val bearing = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(from.lat)
    val lng1 = Math.toRadians(from.lng)
    val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
    val lng2 = lng1 + atan2(sin(bearing) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
    val lng = (Math.toDegrees(lng2) + 540) % 360 - 180
    return Point(Math.toDegrees(lat2).coerceIn(-90.0, 90.0), lng)
}

/** A closed ring of points approximating a circle on the ground (first point repeated at the end). */
internal fun circleRing(center: Point, radius: Distance, steps: Int = 64): List<Point> =
    (0..steps).map { destination(center, radius.meters, 360.0 * (it % steps) / steps) }

internal data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** All the points are (nearly) the same spot: zoom to it instead of fitting. */
    val isSpot: Boolean get() = north - south < 1e-6 && east - west < 1e-6
}

internal fun boundsOf(points: List<Point>): Bounds? {
    if (points.isEmpty()) return null
    return Bounds(
        south = points.minOf { it.lat },
        west = points.minOf { it.lng },
        north = points.maxOf { it.lat },
        east = points.maxOf { it.lng },
    )
}

private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/** Shows a pin at [point] in any maps app. */
internal fun geoUri(point: Point, label: String?): String {
    val at = "${point.lat},${point.lng}"
    return "geo:$at?q=$at" + if (label.isNullOrBlank()) "" else "(${encode(label)})"
}

/** Searches for [query] in any maps app. */
internal fun geoUri(query: String): String = "geo:0,0?q=" + encode(query)

/** Opens Google Maps (app or website) on [query] when no app handles `geo:`. */
internal fun searchUrl(query: String): String = "https://www.google.com/maps/search/?api=1&query=" + encode(query)

/** Route preview to [destination] (an address or "lat,lng"): Google Maps app if installed, otherwise the website. */
internal fun directionsUrl(destination: String, travel: Travel): String {
    val mode = when (travel) {
        Travel.Driving -> "driving"
        Travel.Walking -> "walking"
        Travel.Transit -> "transit"
        Travel.Biking -> "bicycling"
    }
    return "https://www.google.com/maps/dir/?api=1&destination=${encode(destination)}&travelmode=$mode"
}

internal fun Point.query(): String = "$lat,$lng"

/**
 * The size to draw a marker icon at: its longest side becomes [target], keeping its shape.
 * Drawables with no size of their own (e.g. a plain color) become a [target] square.
 */
internal fun iconSize(width: Int, height: Int, target: Int): Pair<Int, Int> = when {
    width <= 0 || height <= 0 -> target to target
    width >= height -> target to (target.toDouble() * height / width).roundToInt().coerceAtLeast(1)
    else -> (target.toDouble() * width / height).roundToInt().coerceAtLeast(1) to target
}

/** How far along its lifecycle a map is. */
internal enum class Stage { Created, Started, Resumed }

internal enum class Step { Start, Resume, Pause, Stop }

/** The calls that bring a map from [from] to [to], in order. */
internal fun stepsBetween(from: Stage, to: Stage): List<Step> {
    val steps = mutableListOf<Step>()
    var at = from
    while (at < to) {
        steps += if (at == Stage.Created) Step.Start else Step.Resume
        at = Stage.entries[at.ordinal + 1]
    }
    while (at > to) {
        steps += if (at == Stage.Resumed) Step.Pause else Step.Stop
        at = Stage.entries[at.ordinal - 1]
    }
    return steps
}

// endregion
