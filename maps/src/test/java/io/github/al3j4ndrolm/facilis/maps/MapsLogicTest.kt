package io.github.al3j4ndrolm.facilis.maps

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
}
