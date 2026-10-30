package io.github.al3j4ndrolm.facilis.maps

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Polygon
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import org.maplibre.android.maps.MapView as MapLibreView
import org.maplibre.geojson.Point as GeoPoint

/**
 * A pin on the map. [data] comes back in `onMarkerTap`, so you can tell markers apart.
 *
 * In Compose, a marker can also be your own composable: `Marker(point) { Avatar(user) }`.
 *
 * @param color the pin's color. Not used with an [icon].
 * @param icon shown instead of the pin, with its bottom middle on [point]: a drawable
 *   (`icon = R.drawable.ic_cafe`) or an image ([MarkerIcon.Image]).
 */
data class Marker(
    val point: Point,
    val title: String? = null,
    val color: Int = DEFAULT_MARKER_COLOR,
    val data: Any? = null,
    val icon: MarkerIcon? = null,
) {
    /** A marker showing the drawable [icon] (vector or image), e.g. `R.drawable.ic_cafe`. */
    constructor(
        point: Point,
        title: String? = null,
        color: Int = DEFAULT_MARKER_COLOR,
        data: Any? = null,
        @DrawableRes icon: Int,
    ) : this(point, title, color, data, MarkerIcon.Resource(icon))
}

/** A custom look for a [Marker]. */
interface MarkerIcon {

    /** A drawable, drawn [MARKER_ICON_SIZE_DP] dp on its longest side, in its own colors. */
    data class Resource(@DrawableRes val id: Int) : MarkerIcon

    /** Any image, drawn at its own pixel size. Two images are the same icon only if they're the same object. */
    class Image(val bitmap: Bitmap) : MarkerIcon {
        internal val key = nextImageKey.incrementAndGet()

        private companion object {
            val nextImageKey = AtomicLong()
        }
    }
}

/** Something drawn on the map: a [Line] or a [Circle]. */
sealed interface Shape

/** A line through [points], e.g. a run you recorded. */
data class Line(val points: List<Point>, val color: Int = DEFAULT_SHAPE_COLOR, val width: Float = 4f) : Shape

/** A shaded circle of [radius] around [center], e.g. "within 500 m". */
data class Circle(val center: Point, val radius: Distance, val color: Int = DEFAULT_SHAPE_COLOR) : Shape

enum class MapStyle {
    /** Light or dark, following the system theme. */
    Auto,
    Light,
    Dark,
}

const val DEFAULT_MARKER_COLOR: Int = 0xFFE53935.toInt()
const val DEFAULT_SHAPE_COLOR: Int = 0xFF1E88E5.toInt()

/** How big a [MarkerIcon.Resource] is drawn, on its longest side. */
const val MARKER_ICON_SIZE_DP: Int = 36

/**
 * A map with no setup: no API key, no lifecycle calls to forward. Use it in XML or create it in
 * code, then:
 *
 * ```
 * mapView.show(center = sjsu, markers = listOf(Marker(sjsu, "Class")))
 * mapView.onMarkerTap = { marker -> }
 * ```
 */
class MapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    /** The pins on the map. Setting it redraws them. */
    var markers: List<Marker> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            style?.let { renderMarkers(it) }
        }

    /** Lines and circles drawn on the map. */
    var shapes: List<Shape> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            style?.let { renderShapes(it) }
        }

    /** Shows the user's location as a blue dot. Asks for the location permission the first time. */
    var showMyLocation: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateMyLocation()
        }

    var mapStyle: MapStyle = MapStyle.Auto
        set(value) {
            if (field == value) return
            field = value
            loadStyle()
        }

    /**
     * Which way the top of the map points: [Heading.North], the way the user walks
     * ([Heading.Walking]), the way the phone points ([Heading.Phone]), a fixed direction or toward a place.
     */
    var heading: Heading = Heading.North
        set(value) {
            if (field == value) return
            field = value
            applyHeading()
        }

    /** Runs when the user taps the map (not a marker). */
    var onTap: ((Point) -> Unit)? = null

    /** Runs when the user long-presses the map. */
    var onLongPress: ((Point) -> Unit)? = null

    /** Runs when the user taps a marker. */
    var onMarkerTap: ((Marker) -> Unit)? = null

    /** Runs with the new center each time the map stops moving. */
    var onMoved: ((Point) -> Unit)? = null

    /** The point in the middle of the map. Setting it moves the map there. */
    var center: Point
        get() = map?.cameraPosition?.target?.toPoint() ?: (pendingCamera as? Camera.MoveTo)?.point ?: Point(0.0, 0.0)
        set(value) = moveTo(value)

    /** 0 shows the whole world, 15 a neighborhood, 20 a building. */
    var zoom: Double
        get() = map?.cameraPosition?.zoom ?: (pendingCamera as? Camera.MoveTo)?.zoom ?: WORLD_ZOOM
        set(value) = moveTo(center, value)

    private val inner: MapLibreView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private val pins = HashSet<String>()
    private val brokenIcons = HashSet<Int>()
    private val scope = MainScope()

    private sealed interface Camera {
        data class MoveTo(val point: Point, val zoom: Double?, val animate: Boolean) : Camera
        data class Fit(val points: List<Point>, val animate: Boolean) : Camera
    }

    /** A camera move asked for before the map was ready. */
    private var pendingCamera: Camera? = null
    private var cameraReady = false
    private var askedForLocation = false
    private var cameraMoves = 0

    /** Where the camera is headed while it moves, so a turn of the map carries on the move under way. */
    private var goingTo: CameraPosition? = null
    private var arrivesAt = 0L
    private var headingJob: Job? = null

    private var owner: LifecycleOwner? = null
    private var attached = false
    private var stage = Stage.Created
    private var destroyed = false
    private val observer = LifecycleEventObserver { _, _ -> syncLifecycle() }

    init {
        MapLibre.getInstance(context.applicationContext)
        inner = MapLibreView(context)
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inner.onCreate(null)
        inner.getMapAsync { m ->
            map = m
            listen(m)
            loadStyle()
            inner.doOnLayout { applyFirstCamera() }
        }
    }

    /** Sets several things at once. Leave a value out to keep it. */
    fun show(
        center: Point? = null,
        zoom: Double? = null,
        markers: List<Marker> = this.markers,
        shapes: List<Shape> = this.shapes,
    ) {
        this.markers = markers
        this.shapes = shapes
        if (center != null) moveTo(center, zoom)
    }

    /** Moves the map to [point]. Without a [zoom], it zooms in to street level if it's further out. */
    fun moveTo(point: Point, zoom: Double? = null, animate: Boolean = true) {
        cameraMoves++
        val m = map
        if (m == null || !cameraReady) {
            pendingCamera = Camera.MoveTo(point, zoom, animate)
            return
        }
        val here = goingTo ?: m.cameraPosition
        val target = zoom ?: here.zoom.takeIf { it >= MIN_STREET_ZOOM } ?: STREET_ZOOM
        val bearing = (heading as? Heading.Towards)?.let { point.bearingTo(it.point) } ?: here.bearing
        val position = CameraPosition.Builder(here).target(point.toLatLng()).zoom(target).bearing(bearing).build()
        // A spot already on screen (e.g. following the user) slides there, without the zoom out and
        // back in of a fly, which makes the labels redraw.
        val near = target == here.zoom && m.projection.visibleRegion.latLngBounds.contains(point.toLatLng())
        go(m, position, animate, fly = !near)
    }

    /** Moves and zooms the map so all [points] show. Defaults to every marker and shape. */
    fun fit(points: List<Point> = everything(), animate: Boolean = true) {
        cameraMoves++
        val m = map
        if (m == null || !cameraReady) {
            pendingCamera = Camera.Fit(points, animate)
            return
        }
        val b = boundsOf(points) ?: return
        if (b.isSpot) return moveTo(Point(b.north, b.east), STREET_ZOOM, animate)
        val padding = (FIT_PADDING_DP * resources.displayMetrics.density).toInt()
        val here = goingTo ?: m.cameraPosition
        val bounds = LatLngBounds.from(b.north, b.east, b.south, b.west)
        val position = m.getCameraForLatLngBounds(bounds, IntArray(4) { padding }, here.bearing, here.tilt) ?: return
        go(m, position, animate, fly = true)
    }

    /**
     * Frees the map. Called for you when the screen it's on is destroyed; call it yourself only
     * if you remove the view from a screen that stays open.
     */
    fun destroy() {
        if (destroyed) return
        stepTo(Stage.Created)
        owner?.lifecycle?.removeObserver(observer)
        owner = null
        destroyed = true
        scope.cancel()
        inner.onDestroy()
    }

    // region Lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        owner = findViewTreeLifecycleOwner()?.also { it.lifecycle.addObserver(observer) }
        syncLifecycle()
    }

    override fun onDetachedFromWindow() {
        attached = false
        owner?.lifecycle?.removeObserver(observer)
        owner = null
        syncLifecycle()
        super.onDetachedFromWindow()
    }

    private fun syncLifecycle() {
        if (destroyed) return
        val state = owner?.lifecycle?.currentState
        if (state == Lifecycle.State.DESTROYED) return destroy()
        val target = when {
            !attached -> Stage.Created
            state == null || state == Lifecycle.State.RESUMED -> Stage.Resumed
            state == Lifecycle.State.STARTED -> Stage.Started
            else -> Stage.Created
        }
        stepTo(target)
    }

    private fun stepTo(target: Stage) {
        stepsBetween(stage, target).forEach {
            when (it) {
                Step.Start -> inner.onStart()
                Step.Resume -> inner.onResume()
                Step.Pause -> inner.onPause()
                Step.Stop -> inner.onStop()
            }
        }
        stage = target
        followHeading()
    }

    // endregion

    // region Map

    private fun listen(m: MapLibreMap) {
        m.addOnMapClickListener { latLng ->
            val marker = markerAt(m, latLng)
            val onMarker = onMarkerTap
            val onMap = onTap
            when {
                marker != null && onMarker != null -> onMarker(marker).let { true }
                onMap != null -> onMap(latLng.toPoint()).let { true }
                else -> false
            }
        }
        m.addOnMapLongClickListener { latLng ->
            val handler = onLongPress ?: return@addOnMapLongClickListener false
            handler(latLng.toPoint())
            true
        }
        m.addOnCameraIdleListener { onMoved?.invoke(center) }
    }

    private fun markerAt(m: MapLibreMap, latLng: LatLng): Marker? {
        val at = m.projection.toScreenLocation(latLng)
        val slop = TAP_SLOP_DP * resources.displayMetrics.density
        val hit = m.queryRenderedFeatures(RectF(at.x - slop, at.y - slop, at.x + slop, at.y + slop), MARKER_LAYER)
        val index = hit.firstOrNull()?.getNumberProperty(PROP_INDEX)?.toInt() ?: return null
        return markers.getOrNull(index)
    }

    private fun applyFirstCamera() {
        cameraReady = true
        when (val pending = pendingCamera) {
            is Camera.MoveTo -> moveTo(pending.point, pending.zoom, animate = false)
            is Camera.Fit -> fit(pending.points, animate = false)
            null -> if (everything().isNotEmpty()) fit(animate = false) else if (showMyLocation) goToMyLocation()
        }
        pendingCamera = null
        applyHeading()
    }

    private fun goToMyLocation() {
        val movesBefore = cameraMoves
        scope.launch {
            val here = location.last() ?: location.current() ?: return@launch
            // Don't jump if the app moved the map meanwhile.
            if (cameraMoves == movesBefore) moveTo(here, STREET_ZOOM)
        }
    }

    /**
     * Moves the camera to [position]. MapLibre runs one camera move at a time and a new one cancels
     * the last, so every move goes through here and starts from where the last one was headed.
     */
    private fun go(
        m: MapLibreMap,
        position: CameraPosition,
        animate: Boolean,
        fly: Boolean = false,
        smooth: Boolean = true,
        ms: Int = ANIMATION_MS,
    ) {
        val update = CameraUpdateFactory.newCameraPosition(position)
        if (!animate) {
            goingTo = null
            m.moveCamera(update)
            return
        }
        goingTo = position
        arrivesAt = SystemClock.uptimeMillis() + ms
        val done = object : MapLibreMap.CancelableCallback {
            override fun onCancel() = arrived(position)
            override fun onFinish() = arrived(position)
        }
        if (fly) m.animateCamera(update, ms, done) else m.easeCamera(update, ms, smooth, done)
    }

    private fun arrived(position: CameraPosition) {
        if (goingTo === position) goingTo = null
    }

    /** Turns the map so its top points to [degrees], finishing any move under way at the same time. */
    private fun turnTo(degrees: Double, smooth: Boolean = true, ms: Int = ANIMATION_MS) {
        val m = map ?: return
        if (!cameraReady) return
        val here = goingTo ?: m.cameraPosition
        if (angleBetween(here.bearing, degrees) < MIN_TURN_DEGREES) return
        // Don't rush a move under way (e.g. following the user): give the turn at least its time left.
        val left = if (goingTo != null) (arrivesAt - SystemClock.uptimeMillis()).toInt() else 0
        go(m, CameraPosition.Builder(here).bearing(degrees).build(), animate = true, smooth = smooth, ms = maxOf(ms, left))
    }

    private fun applyHeading() {
        headingJob?.cancel()
        headingJob = null
        val m = map ?: return
        if (!cameraReady) return
        when (val h = heading) {
            Heading.North -> turnTo(0.0)
            is Heading.Degrees -> turnTo(h.degrees)
            is Heading.Towards -> (goingTo ?: m.cameraPosition).target?.let { turnTo(it.toPoint().bearingTo(h.point)) }
            Heading.Walking, Heading.Phone -> followHeading()
        }
    }

    /** Keeps turning the map for [Heading.Walking] or [Heading.Phone], while the map is on screen. */
    private fun followHeading() {
        val live = heading == Heading.Walking || heading == Heading.Phone
        if (!live || stage != Stage.Resumed || !cameraReady || destroyed) {
            headingJob?.cancel()
            headingJob = null
            return
        }
        if (headingJob != null) return
        headingJob = scope.launch {
            if (heading == Heading.Walking) {
                walkingDirections().collect { turnTo(it) }
            } else {
                compassDirections(context).collect { turnTo(it, smooth = false, ms = COMPASS_TURN_MS) }
            }
        }
    }

    private fun everything(): List<Point> = markers.map { it.point } + shapes.flatMap { shape ->
        when (shape) {
            is Line -> shape.points
            is Circle -> listOf(0.0, 90.0, 180.0, 270.0).map { destination(shape.center, shape.radius.meters, it) }
        }
    }

    private val isDark: Boolean
        get() = when (mapStyle) {
            MapStyle.Light -> false
            MapStyle.Dark -> true
            MapStyle.Auto -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }

    private fun loadStyle() {
        val m = map ?: return
        style = null
        pins.clear()
        val url = if (isDark) FacilisMaps.darkStyle else FacilisMaps.lightStyle
        m.setStyle(Style.Builder().fromUri(url)) { loaded ->
            style = loaded
            addLayers(loaded)
            renderShapes(loaded)
            renderMarkers(loaded)
            updateMyLocation()
        }
    }

    private fun addLayers(s: Style) {
        s.addSource(GeoJsonSource(AREAS))
        s.addSource(GeoJsonSource(LINES))
        s.addSource(GeoJsonSource(MARKERS))
        val color = Expression.toColor(Expression.get(PROP_COLOR))
        s.addLayer(FillLayer(AREAS, AREAS).withProperties(PropertyFactory.fillColor(color), PropertyFactory.fillOpacity(0.18f)))
        s.addLayer(LineLayer("$AREAS-outline", AREAS).withProperties(PropertyFactory.lineColor(color), PropertyFactory.lineWidth(2f)))
        s.addLayer(
            LineLayer(LINES, LINES).withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(Expression.get(PROP_WIDTH)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        s.addLayer(
            SymbolLayer(MARKER_LAYER, MARKERS).withProperties(
                PropertyFactory.iconImage(Expression.get(PROP_ICON)),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.textField(Expression.get(PROP_TITLE)),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(12f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 0.2f)),
                PropertyFactory.textOptional(true),
                PropertyFactory.textColor(if (isDark) Color.WHITE else 0xFF202124.toInt()),
                PropertyFactory.textHaloColor(if (isDark) Color.BLACK else Color.WHITE),
                PropertyFactory.textHaloWidth(1.5f),
            )
        )
    }

    private fun renderMarkers(s: Style) {
        val used = HashSet<String>()
        val features = markers.mapIndexed { i, marker ->
            Feature.fromGeometry(marker.point.toGeo()).apply {
                addNumberProperty(PROP_INDEX, i)
                addStringProperty(PROP_ICON, image(s, marker).also { used += it })
                marker.title?.let { addStringProperty(PROP_TITLE, it) }
            }
        }
        s.getSourceAs<GeoJsonSource>(MARKERS)?.setGeoJson(FeatureCollection.fromFeatures(features))
        // Images (e.g. a composable redrawn with a new count) can change often: drop the old ones.
        pins.filter { it.startsWith(IMAGE_PREFIX) && it !in used }.forEach {
            s.removeImage(it)
            pins -= it
        }
    }

    private fun renderShapes(s: Style) {
        val lines = shapes.filterIsInstance<Line>().filter { it.points.size >= 2 }.map { line ->
            Feature.fromGeometry(LineString.fromLngLats(line.points.map { it.toGeo() })).apply {
                addStringProperty(PROP_COLOR, cssColor(line.color))
                addNumberProperty(PROP_WIDTH, line.width)
            }
        }
        val areas = shapes.filterIsInstance<Circle>().map { circle ->
            Feature.fromGeometry(Polygon.fromLngLats(listOf(circleRing(circle.center, circle.radius).map { it.toGeo() }))).apply {
                addStringProperty(PROP_COLOR, cssColor(circle.color))
            }
        }
        s.getSourceAs<GeoJsonSource>(LINES)?.setGeoJson(FeatureCollection.fromFeatures(lines))
        s.getSourceAs<GeoJsonSource>(AREAS)?.setGeoJson(FeatureCollection.fromFeatures(areas))
    }

    /** The image id for [marker]: its icon, or a pin in its color. Added to the style the first time. */
    private fun image(s: Style, marker: Marker): String = when (val icon = marker.icon) {
        null -> pin(s, marker.color)
        is MarkerIcon.Image -> (IMAGE_PREFIX + icon.key).also { if (pins.add(it)) s.addImage(it, icon.bitmap) }
        is MarkerIcon.Resource -> {
            val id = "facilis-icon-${icon.id}"
            when {
                id in pins -> id
                else -> iconBitmap(icon.id)?.let { bitmap ->
                    pins.add(id)
                    s.addImage(id, bitmap)
                    id
                } ?: pin(s, marker.color)
            }
        }
        // A composable marker outside a Compose Map, which is what draws those.
        else -> pin(s, marker.color)
    }

    private fun pin(s: Style, color: Int): String {
        val id = "facilis-pin-" + Integer.toHexString(color)
        if (pins.add(id)) s.addImage(id, pinBitmap(color))
        return id
    }

    /** Draws the drawable [res] at marker size, or null (falling back to the pin) if it can't be loaded. */
    private fun iconBitmap(@DrawableRes res: Int): Bitmap? {
        if (res in brokenIcons) return null
        val drawable = try {
            ContextCompat.getDrawable(context, res)
        } catch (e: Resources.NotFoundException) {
            warn("Marker icon $res isn't a drawable, so a pin is shown instead.", e)
            null
        }
        if (drawable == null) {
            brokenIcons += res
            return null
        }
        val target = (MARKER_ICON_SIZE_DP * resources.displayMetrics.density).roundToInt()
        val (width, height) = iconSize(drawable.intrinsicWidth, drawable.intrinsicHeight, target)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(it))
        }
    }

    private fun pinBitmap(color: Int): Bitmap {
        val d = resources.displayMetrics.density
        val width = 30 * d
        val height = 40 * d
        val stroke = 1.5f * d
        val r = width / 2 - stroke
        val cx = width / 2
        val cy = r + stroke
        val tip = height - stroke
        val shape = Path().apply {
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 135f, 270f)
            lineTo(cx, tip)
            close()
        }
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = stroke
            })
            drawCircle(cx, cy, r * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
        }
        return bitmap
    }

    @SuppressLint("MissingPermission") // checked just above
    private fun updateMyLocation() {
        val m = map ?: return
        val s = style ?: return
        val component = m.locationComponent
        if (!showMyLocation) {
            if (component.isLocationComponentActivated) component.isLocationComponentEnabled = false
            return
        }
        if (!location.allowed) {
            if (askedForLocation) return
            askedForLocation = true
            scope.launch {
                // The dialog can't show before the screen does (e.g. behind the lock screen).
                findViewTreeLifecycleOwner()?.lifecycle?.currentStateFlow?.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                if (location.requestPermission()) updateMyLocation()
            }
            return
        }
        if (!component.isLocationComponentActivated) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.Builder(context, s).useDefaultLocationEngine(true).build()
            )
        }
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.NORMAL
        component.cameraMode = CameraMode.NONE
    }

    // endregion

    private companion object {
        const val AREAS = "facilis-areas"
        const val LINES = "facilis-lines"
        const val MARKERS = "facilis-markers"
        const val MARKER_LAYER = "facilis-markers"
        const val PROP_INDEX = "i"
        const val PROP_ICON = "icon"
        const val IMAGE_PREFIX = "facilis-image-"
        const val PROP_TITLE = "title"
        const val PROP_COLOR = "color"
        const val PROP_WIDTH = "width"
        const val WORLD_ZOOM = 1.0
        const val STREET_ZOOM = 15.0
        const val MIN_STREET_ZOOM = 12.0
        const val FIT_PADDING_DP = 48
        const val TAP_SLOP_DP = 12
        const val ANIMATION_MS = 600
        const val COMPASS_TURN_MS = 150
        const val MIN_TURN_DEGREES = 1.0
    }
}

internal fun LatLng.toPoint() = Point(latitude.coerceIn(-90.0, 90.0), (longitude + 540) % 360 - 180)

internal fun Point.toLatLng() = LatLng(lat, lng)

private fun Point.toGeo(): GeoPoint = GeoPoint.fromLngLat(lng, lat)

private fun cssColor(color: Int) =
    "rgba(${Color.red(color)}, ${Color.green(color)}, ${Color.blue(color)}, ${Color.alpha(color) / 255.0})"
