package io.github.al3j4ndrolm.facilis.maps.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import io.github.al3j4ndrolm.facilis.maps.MarkerIcon

/**
 * A marker icon drawn from your own composable:
 *
 * ```
 * val avatar = rememberMarkerIcon { Avatar(user, Modifier.size(40.dp)) }
 * Map(markers = listOf(Marker(here, "Alex", icon = avatar)))
 * ```
 *
 * The composable is drawn once into a still image, so the map stays fast with many markers.
 * Animations don't play and buttons inside can't be tapped (the whole marker still triggers
 * `onMarkerTap`). It's redrawn only when one of [keys] changes, so pass whatever it shows:
 * `rememberMarkerIcon(unread) { Badge(unread) }`. Give it a size, e.g. with `Modifier.size`.
 * Your theme and other CompositionLocals apply.
 */
@Composable
fun rememberMarkerIcon(vararg keys: Any?, content: @Composable () -> Unit): MarkerIcon {
    val parent = LocalView.current as ViewGroup
    val compositionContext = rememberCompositionContext()
    var later by remember { mutableStateOf<MarkerIcon?>(null) }
    val now = remember(*keys) { draw(parent, compositionContext, content)?.let { MarkerIcon.Image(it) } }
    if (now == null) {
        // The screen's first composition runs before it's attached, when nothing can be drawn yet.
        LaunchedEffect(*keys) {
            later = MarkerIcon.Image(checkNotNull(draw(parent, compositionContext, content)) { "The screen isn't showing." })
        }
    }
    // Until then: the previous image, or nothing, rather than a flash of the default pin.
    return now ?: later ?: blank
}

private val blank by lazy { MarkerIcon.Image(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }

/**
 * Composes [content] in a temporary view next to the screen's content and draws it into a
 * bitmap, or returns null if the screen isn't attached yet.
 */
private fun draw(parent: ViewGroup, compositionContext: CompositionContext, content: @Composable () -> Unit): Bitmap? {
    val view = ComposeView(parent.context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setParentCompositionContext(compositionContext)
        setContent(content)
    }
    parent.addView(view)
    try {
        if (!view.isAttachedToWindow) return null
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        val width = view.measuredWidth
        val height = view.measuredHeight
        check(width > 0 && height > 0) {
            "The rememberMarkerIcon content has no size (${width}x$height). Give it one, e.g. Modifier.size(40.dp)."
        }
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    } finally {
        parent.removeView(view)
    }
}
