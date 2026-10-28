package io.github.al3j4ndrolm.facilis.maps.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.al3j4ndrolm.facilis.maps.Circle
import io.github.al3j4ndrolm.facilis.maps.Line
import io.github.al3j4ndrolm.facilis.maps.Marker
import io.github.al3j4ndrolm.facilis.maps.Point
import io.github.al3j4ndrolm.facilis.maps.Travel
import io.github.al3j4ndrolm.facilis.maps.compose.Map
import io.github.al3j4ndrolm.facilis.maps.compose.rememberLiveLocation
import io.github.al3j4ndrolm.facilis.maps.compose.rememberLocation
import io.github.al3j4ndrolm.facilis.maps.compose.rememberMarkerIcon
import io.github.al3j4ndrolm.facilis.maps.location
import io.github.al3j4ndrolm.facilis.maps.maps
import io.github.al3j4ndrolm.facilis.maps.meters
import kotlinx.coroutines.launch

private val sjsu = Point(37.3352, -121.8811)
private val engineering = Point(37.33665, -121.88207)
private val library = Point(37.33558, -121.88504)
private val union = Point(37.33603, -121.88114)

private val campus = listOf(
    Marker(engineering, "Engineering", data = "room-189"),
    Marker(library, "King Library", data = "library"),
    Marker(union, "Student Union", data = "union"),
    // Custom icons: a vector from this app, and an image from Android itself.
    Marker(Point(37.33490, -121.88330), "Coffee", icon = R.drawable.ic_cafe),
    Marker(Point(37.33420, -121.87980), "Favorite", icon = android.R.drawable.star_big_on),
)

private val alexSpot = Point(37.33600, -121.87920)

/** The walk from the library to class, as a recorded route would look. */
private val route = listOf(library, Point(37.33580, -121.88350), union, engineering)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Demo() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Demo() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Tap a pin, or long-press the map to drop one.") }
    var center by remember { mutableStateOf<Point?>(null) }
    var dropped by remember { mutableStateOf(listOf<Marker>()) }
    var waves by remember { mutableIntStateOf(0) }
    // A marker drawn from a composable; redrawn when the wave count changes.
    val alex = rememberMarkerIcon(waves) { FriendBubble(initials = "AL", waves = waves) }
    // Where you were when the screen opened, and where you are now.
    val start = rememberLocation()
    val live = rememberLiveLocation()
    // Map<K, V> still means Kotlin's map next to the Map composable.
    val counts: Map<String, Int> = mapOf("pins" to campus.size)

    Column(Modifier.fillMaxSize()) {
        Map(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            center = center,
            markers = campus + Marker(alexSpot, "Alex", data = "alex", icon = alex) + dropped,
            shapes = listOf(Circle(sjsu, 400.meters), Line(route)),
            showMyLocation = true,
            onMarkerTap = { marker ->
                if (marker.data == "alex") {
                    waves++
                    status = "You waved at Alex $waves time(s)"
                    return@Map
                }
                status = "${marker.title}${marker.data?.let { " ($it)" } ?: ""}, ${marker.point.distanceTo(sjsu)} from the middle of campus" },
            onLongPress = { point ->
                dropped = dropped + Marker(point, "Pin ${dropped.size + 1}", color = 0xFF43A047.toInt())
                status = "Dropped a pin at $point"
            },
        )
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            Text(
                if (live == null) "Finding you…" else "Live: ${live.distanceTo(sjsu)} from SJSU (started ${start?.distanceTo(sjsu) ?: "?"})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(status, style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 3)
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    scope.launch {
                        status = "Finding you…"
                        val here = location.current()
                        status = if (here == null) {
                            "No location: permission denied or location off."
                        } else {
                            center = here
                            "You're at ${location.address(here) ?: here}, ${here.distanceTo(sjsu)} from SJSU"
                        }
                    }
                }) { Text("Where am I") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val found = location.find("1 Washington Sq, San Jose, CA")
                        status = "1 Washington Sq is at ${found ?: "?"} (${counts["pins"]} campus pins)"
                        if (found != null) center = found
                    }
                }) { Text("Find address") }
                OutlinedButton(onClick = { maps.open(engineering, label = "Engineering") }) { Text("Open in maps") }
                OutlinedButton(onClick = { maps.directions(to = engineering, by = Travel.Walking) }) { Text("Walk to class") }
            }
        }
    }
}

/** A friend's avatar with a badge counting your waves: the content of a composable marker. */
@Composable
private fun FriendBubble(initials: String, waves: Int) {
    Box(Modifier.size(48.dp)) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(42.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        if (waves > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .background(Color(0xFFE53935), CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$waves", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
