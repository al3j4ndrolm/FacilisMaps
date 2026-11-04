package io.github.al3j4ndrolm.facilis.maps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.al3j4ndrolm.facilis.permission.Permission
import io.github.al3j4ndrolm.facilis.permission.permissions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The user's steps, counted by the phone, with no setup. Asks for the physical activity
 * permission itself the first time.
 *
 * ```
 * steps.watch().collect { taken -> }   // 0, 1, 2, ... since you started collecting
 * ```
 */
val steps: FacilisSteps get() = FacilisSteps

object FacilisSteps {

    private val context: Context get() = Tracker.appContext
    private val sensors: SensorManager? get() = context.getSystemService(SensorManager::class.java)

    /** Whether this phone can count steps. Most phones can; some tablets and emulators can't. */
    val available: Boolean
        get() = sensors?.let { it.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: it.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) } != null

    /** Asks for the physical activity permission if needed. True if it's allowed. */
    suspend fun requestPermission(): Boolean =
        permissions.request(Permission.PhysicalActivity)
            .also { if (!it) warn("Physical activity permission denied, so steps can't be counted.") }

    /**
     * The steps taken since you started collecting this: 0 first, then each time the phone counts
     * more. It keeps counting while the screen is off. Asks for the permission if needed, and ends
     * right away if it's denied or the phone can't count steps.
     *
     * ```
     * // In a ViewModel, during a workout:
     * counting = viewModelScope.launch { steps.watch().collect { _steps.value = it } }
     * ```
     */
    fun watch(): Flow<Int> = callbackFlow {
        if (!requestPermission()) {
            close()
            return@callbackFlow
        }
        val manager = sensors
        val sensor = manager?.let { it.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: it.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) }
        if (manager == null || sensor == null) {
            warn("This phone can't count steps.")
            close()
            return@callbackFlow
        }
        trySend(0)
        val count = StepCount()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val taken = if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) count.total(event.values[0]) else count.step()
                trySend(taken)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { manager.unregisterListener(listener) }
    }.distinctUntilChanged().conflate()
}

/** Steps since the first reading, from either the phone's running total or one step at a time. */
internal class StepCount {
    private var start = -1f
    private var taken = 0

    /** The phone's step counter reports every step since it turned on, so count from its first reading. */
    fun total(sinceBoot: Float): Int {
        if (start < 0 || sinceBoot < start) start = sinceBoot
        taken = (sinceBoot - start).toInt()
        return taken
    }

    /** Phones without a counter report each step as it's taken. */
    fun step(): Int = ++taken
}
