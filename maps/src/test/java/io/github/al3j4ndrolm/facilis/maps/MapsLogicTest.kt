package io.github.al3j4ndrolm.facilis.maps

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsLogicTest {

    private val sjsu = Point(37.3352, -121.8811)
    private val sfo = Point(37.6213, -122.3790)

    // region Distance

    @Test
    fun distanceMatchesKnownValues() {
        // San Jose State to SFO: about 31.8 km north and 43.9 km west, so 54.2 km in a straight line.
        assertEquals(54.2, sjsu.distanceTo(sfo).kilometers, 0.3)
        // A degree of longitude on the equator is 111.2 km.
        assertEquals(111.2, Point(0.0, 0.0).distanceTo(Point(0.0, 1.0)).kilometers, 0.1)
        assertEquals(0.0, sjsu.distanceTo(sjsu).meters, 1e-9)
        // A degree of latitude is about 111 km anywhere.
        assertEquals(111.2, Point(10.0, 0.0).distanceTo(Point(11.0, 0.0)).kilometers, 0.2)
    }

    @Test
    fun distanceReadsNaturally() {
        assertEquals("850 m", 850.meters.toString())
        assertEquals("1.2 km", 1234.meters.toString())
        assertEquals("12 km", 12.3.km.toString())
        assertEquals(1.0, 1609.344.meters.miles, 1e-9)
        assertTrue(500.meters < 1.km)
    }

    @Test
    fun pointsRejectImpossibleCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { Point(91.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Point(0.0, 181.0) }
        assertEquals("37.335200, -121.881100", sjsu.toString())
    }

    // endregion

    // region Shapes and bounds

    @Test
    fun destinationTravelsTheRightDistance() {
        listOf(0.0, 45.0, 90.0, 200.0).forEach { bearing ->
            assertEquals(500.0, metersBetween(sjsu, destination(sjsu, 500.0, bearing)), 0.5)
        }
        // Heading north keeps the longitude.
        assertEquals(sjsu.lng, destination(sjsu, 1000.0, 0.0).lng, 1e-9)
    }

    @Test
    fun circleRingIsClosedAndRound() {
        val ring = circleRing(sjsu, 200.meters, steps = 32)
        assertEquals(33, ring.size)
        assertEquals(ring.first(), ring.last())
        ring.forEach { assertEquals(200.0, metersBetween(sjsu, it), 0.5) }
    }

    @Test
    fun destinationWrapsAroundTheDateLine() {
        val east = destination(Point(0.0, 179.99), 5_000.0, 90.0)
        assertTrue(east.lng < -179)
    }

    @Test
    fun boundsCoverAllPoints() {
        assertNull(boundsOf(emptyList()))
        val b = boundsOf(listOf(sjsu, sfo))!!
        assertEquals(Bounds(south = 37.3352, west = -122.3790, north = 37.6213, east = -121.8811), b)
        assertFalse(b.isSpot)
        assertTrue(boundsOf(listOf(sjsu, sjsu))!!.isSpot)
    }

    @Test
    fun markerIconsFitTheMarkerSize() {
        // A 24 dp vector icon grows; a big photo shrinks; both keep their shape.
        assertEquals(108 to 108, iconSize(72, 72, target = 108))
        assertEquals(108 to 72, iconSize(1200, 800, target = 108))
        assertEquals(54 to 108, iconSize(100, 200, target = 108))
        // A drawable with no size of its own becomes a square.
        assertEquals(108 to 108, iconSize(-1, -1, target = 108))
        // Never zero pixels wide, even for a hairline.
        assertEquals(108 to 1, iconSize(5000, 1, target = 108))
    }

    // endregion

    // region Links to maps apps

    @Test
    fun geoLinksPinAPoint() {
        assertEquals("geo:37.3352,-121.8811?q=37.3352,-121.8811", geoUri(sjsu, label = null))
        assertEquals("geo:37.3352,-121.8811?q=37.3352,-121.8811(MQH%20225%20%26%20lab)", geoUri(sjsu, "MQH 225 & lab"))
        assertEquals("geo:0,0?q=Blue%20Bottle%20Coffee%2C%20San%20Jose", geoUri("Blue Bottle Coffee, San Jose"))
    }

    @Test
    fun directionLinksUseTheTravelMode() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=37.3352%2C-121.8811&travelmode=walking",
            directionsUrl(sjsu.query(), Travel.Walking),
        )
        assertTrue(directionsUrl("SJSU", Travel.Biking).endsWith("travelmode=bicycling"))
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=37.3352%2C-121.8811",
            searchUrl(sjsu.query()),
        )
    }

    // endregion

    // region Lifecycle

    @Test
    fun lifecycleStepsGoInOrder() {
        assertEquals(listOf(Step.Start, Step.Resume), stepsBetween(Stage.Created, Stage.Resumed))
        assertEquals(listOf(Step.Pause, Step.Stop), stepsBetween(Stage.Resumed, Stage.Created))
        assertEquals(listOf(Step.Pause), stepsBetween(Stage.Resumed, Stage.Started))
        assertEquals(emptyList<Step>(), stepsBetween(Stage.Started, Stage.Started))
    }

    // endregion

    // region Directions

    @Test
    fun `bearings point the right way`() {
        val sjsu = Point(37.3352, -121.8811)
        assertEquals(0.0, sjsu.bearingTo(Point(37.3452, -121.8811)), 0.01)
        assertEquals(180.0, sjsu.bearingTo(Point(37.3252, -121.8811)), 0.01)
        assertEquals(90.0, sjsu.bearingTo(Point(37.3352, -121.8711)), 0.1)
        assertEquals(270.0, sjsu.bearingTo(Point(37.3352, -121.8911)), 0.1)
        // Walking 100 m out at any heading and looking back gives that heading.
        for (heading in listOf(10.0, 135.0, 225.0, 350.0)) {
            assertEquals(heading, sjsu.bearingTo(destination(sjsu, 100.0, heading)), 0.01)
        }
    }

    @Test
    fun `angles are compared the short way around`() {
        assertEquals(2.0, angleBetween(359.0, 1.0), 1e-9)
        assertEquals(2.0, angleBetween(1.0, 359.0), 1e-9)
        assertEquals(180.0, angleBetween(0.0, 180.0), 1e-9)
        assertEquals(90.0, angleBetween(-45.0, 45.0), 1e-9)
        assertEquals(0.0, angleBetween(720.0, 0.0), 1e-9)
    }

    // endregion

    // region Saving

    @Test
    fun `points and distances survive saving`() {
        val paths = listOf(listOf(Point(37.3352, -121.8811), Point(37.3360, -121.8820)), emptyList())
        assertEquals(paths, Json.decodeFromString<List<List<Point>>>(Json.encodeToString(paths)))
        assertEquals(1.5.km, Json.decodeFromString<Distance>(Json.encodeToString(1.5.km)))
        assertEquals("""{"lat":1.0,"lng":2.0}""", Json.encodeToString(Point(1.0, 2.0)))
    }

    @Test
    fun `a saved point out of range is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Json.decodeFromString<Point>("""{"lat":91.0,"lng":0.0}""") }
    }

    // endregion
}
