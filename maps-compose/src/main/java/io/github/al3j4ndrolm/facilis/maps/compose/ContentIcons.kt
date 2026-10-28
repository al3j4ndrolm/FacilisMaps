package io.github.al3j4ndrolm.facilis.maps.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ComposeView
import io.github.al3j4ndrolm.facilis.maps.MapView
import io.github.al3j4ndrolm.facilis.maps.Marker
import io.github.al3j4ndrolm.facilis.maps.MarkerIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps each composable marker composed, out of sight inside [map], and draws it into a picture
 * again whenever it changes. The map itself only ever gets the pictures.
 */
internal class ContentIcons(private val map: MapView, private val composition: CompositionContext) {

    private val host = Host(map.context)
    private val icons = HashMap<ContentIcon, Drawn>()
    private val scope = MainScope()

    /** The markers as the app gave them, and the picture each one showed last. */
    private var markers: List<Marker> = emptyList()
    private var shown: List<MarkerIcon?> = emptyList()

    private class Drawn(val view: ComposeView) {
        /** Where the composable records its drawing, for a picture that keeps clips and shadows. */
        var layer: GraphicsLayer? = null
        var image: MarkerIcon.Image? = null
        var job: Job? = null
        var warned = false
    }

    init {
        map.addView(host, FrameLayout.LayoutParams(0, 0))
    }

    /** Shows [markers] on the map, each composable swapped for its latest picture. */
    fun show(markers: List<Marker>) {
        this.markers = markers
        val wanted = markers.mapNotNullTo(HashSet()) { it.icon as? ContentIcon }
        icons.keys.filter { it !in wanted }.forEach { gone ->
            icons.remove(gone)!!.let {
                it.job?.cancel()
                host.removeView(it.view)
            }
        }
        wanted.filter { it !in icons }.forEach(::add)
        val resolved = markers.mapIndexed { i, marker ->
            val icon = marker.icon as? ContentIcon ?: return@mapIndexed marker
            // Until a new composable is drawn, keep what this marker showed, so it doesn't flash.
            marker.copy(icon = icons[icon]?.image ?: shown.getOrNull(i) ?: blank)
        }
        shown = resolved.map { it.icon }
        map.markers = resolved
    }

    /** The marker the app gave for [shownMarker], which the map has with a picture instead. */
    fun original(shownMarker: Marker): Marker = markers.getOrNull(map.markers.indexOf(shownMarker)) ?: shownMarker

    /** Stops drawing, when the map goes away. */
    fun release() = scope.cancel()

    private fun add(icon: ContentIcon) {
        val view = ComposeView(map.context)
        val drawn = Drawn(view)
        view.setParentCompositionContext(composition)
        view.setContent { Recorded({ drawn.layer = it }, icon.content) }
        icons[icon] = drawn
        host.addView(view)
        redraw(drawn)
    }

    /** Draws [drawn] into a new picture, and updates the map if it looks different. */
    private fun redraw(drawn: Drawn) {
        val view = drawn.view
        if (!view.isAttachedToWindow) return
        view.measure(UNSPECIFIED, UNSPECIFIED)
        val width = view.measuredWidth
        val height = view.measuredHeight
        if (width <= 0 || height <= 0) {
            if (!drawn.warned) Log.w("FacilisMaps", "A composable marker has no size, so it can't be drawn. Give it one, e.g. Modifier.size(40.dp).")
            drawn.warned = true
            return
        }
        view.layout(0, 0, width, height)
        // Drawing it makes Recorded record the latest content into its layer; the canvas itself is thrown away.
        view.draw(Canvas(scratch))
        val layer = drawn.layer ?: return
        drawn.job?.cancel()
        drawn.job = scope.launch {
            val bitmap = layer.toImageBitmap().asAndroidBitmap().let {
                // The map needs pixels it can read, not a GPU-only picture.
                if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
            }
            if (drawn.image?.bitmap?.sameAs(bitmap) == true) return@launch
            drawn.image = MarkerIcon.Image(bitmap)
            show(markers)
        }
    }

    /**
     * Holds the composables without showing them: it takes no space and draws nothing, but hears
     * when one changes (e.g. a photo finished loading).
     */
    private inner class Host(context: Context) : FrameLayout(context) {
        private val dirty = HashSet<View>()
        private var hasPendingRedraw = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            for (i in 0 until childCount) getChildAt(i).measure(UNSPECIFIED, UNSPECIFIED)
            setMeasuredDimension(0, 0)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (i in 0 until childCount) getChildAt(i).let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        }

        override fun dispatchDraw(canvas: Canvas) = Unit

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            // Composables added before the map was on screen couldn't be drawn yet.
            for (i in 0 until childCount) dirty += getChildAt(i)
            redrawSoon()
        }

        override fun onDescendantInvalidated(child: View, target: View) {
            super.onDescendantInvalidated(child, target)
            dirty += child
            redrawSoon()
        }

        private fun redrawSoon() {
            if (dirty.isEmpty() || hasPendingRedraw) return
            hasPendingRedraw = true
            postOnAnimation {
                hasPendingRedraw = false
                val views = dirty.toList()
                dirty.clear()
                icons.values.filter { it.view in views }.forEach(::redraw)
            }
        }
    }

    private companion object {
        val UNSPECIFIED = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val blank by lazy { MarkerIcon.Image(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }
        val scratch: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    }
}

/** Shows [content] as is, and records each drawing of it into a layer that can become a picture. */
@Composable
private fun Recorded(onLayer: (GraphicsLayer) -> Unit, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    SideEffect { onLayer(layer) }
    Layout(
        content = content,
        modifier = Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() } },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        layout(placeables.maxOfOrNull { it.width } ?: 0, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}
