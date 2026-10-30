package io.github.al3j4ndrolm.facilis.maps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.time.Duration.Companion.seconds

/**
 * Which way the top of the map points.
 *
 * ```
 * Map(center = here, heading = Heading.Walking)
 * ```
 */
sealed interface Heading {

    /** North up. Two fingers can still turn the map. */
    data object North : Heading

    /**
     * The way the user is moving, from GPS. While they stand still the map stays as it is.
     * Asks for the location permission the first time.
     */
    data object Walking : Heading

    /** The way the top of the phone points, from its compass. Turns even while the user stands still. */
    data object Phone : Heading

    /** A fixed direction: 0 is north, 90 east, 180 south, 270 west. */
    data class Degrees(val degrees: Double) : Heading

    /** Toward [point] from the middle of the map, e.g. a destination. */
    data class Towards(val point: Point) : Heading
}

/**
 * The direction the user is moving in, each time it's known: the phone's own reading while they
 * move, otherwise worked out once they've gone a few meters.
 */
internal fun walkingDirections(): Flow<Double> = flow {
    var from: Location? = null
    location.fixes(1.seconds).collect { fix ->
        val start = from
        when {
            fix.hasBearing() && fix.hasSpeed() && fix.speed >= MIN_WALKING_SPEED -> {
                emit(fix.bearing.toDouble())
                from = fix
            }
            start == null -> from = fix
            // GPS drifts a few meters even standing still, so only count clearly longer moves.
            start.distanceTo(fix) >= max(MIN_STEP_METERS, fix.accuracy) -> {
                emit(start.toPoint().bearingTo(fix.toPoint()))
                from = fix
            }
        }
    }
}

/** The way the top of the phone points, as the screen shows it, smoothed so the map doesn't shake. */
internal fun compassDirections(context: Context): Flow<Double> = callbackFlow {
    val sensors = context.getSystemService(SensorManager::class.java)
    val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    if (sensor == null) {
        warn("This phone has no compass, so Heading.Phone leaves the map as it is.")
        close()
        return@callbackFlow
    }
    val rotation = FloatArray(9)
    val remapped = FloatArray(9)
    val angles = FloatArray(3)
    // Averaged as a direction (x, y) rather than degrees, so 359° and 1° average to 0°, not 180°.
    var x = 0.0
    var y = 0.0
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            val (axisX, axisY) = when (ContextCompat.getDisplayOrDefault(context).rotation) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped)
            SensorManager.getOrientation(remapped, angles)
            val azimuth = angles[0].toDouble()
            if (x == 0.0 && y == 0.0) {
                x = cos(azimuth)
                y = sin(azimuth)
            } else {
                x += COMPASS_SMOOTHING * (cos(azimuth) - x)
                y += COMPASS_SMOOTHING * (sin(azimuth) - y)
            }
            trySend((Math.toDegrees(atan2(y, x)) + 360) % 360)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    awaitClose { sensors.unregisterListener(listener) }
}.conflate()

/** About 2.5 km/h; slower than this, the phone's direction of travel is mostly noise. */
private const val MIN_WALKING_SPEED = 0.7f
private const val MIN_STEP_METERS = 8f
private const val COMPASS_SMOOTHING = 0.2
