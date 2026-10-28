package io.github.al3j4ndrolm.facilis.maps

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.startup.Initializer
import java.lang.ref.WeakReference

/**
 * Knows the app's current screen, so calls work without passing a Context.
 * Installed automatically at app start by [FacilisMapsInitializer].
 */
internal object Tracker : Application.ActivityLifecycleCallbacks {

    private var appContextOrNull: Context? = null
    private var resumedRef: WeakReference<Activity>? = null

    val appContext: Context
        get() = appContextOrNull ?: error(
            "FacilisMaps isn't initialized. Make sure androidx.startup's InitializationProvider " +
                "isn't removed from your merged manifest."
        )

    val resumed: Activity? get() = resumedRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun install(context: Context) {
        if (appContextOrNull != null) return
        val app = context.applicationContext as Application
        appContextOrNull = app
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedRef = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedRef?.get() === activity) resumedRef = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumedRef?.get() === activity) resumedRef = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

internal class FacilisMapsInitializer : Initializer<Unit> {
    override fun create(context: Context) = Tracker.install(context)

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

internal fun warn(message: String, error: Throwable? = null) {
    try {
        android.util.Log.w("FacilisMaps", message, error)
    } catch (_: Throwable) {
        System.err.println("FacilisMaps: $message ${error ?: ""}")
    }
}
