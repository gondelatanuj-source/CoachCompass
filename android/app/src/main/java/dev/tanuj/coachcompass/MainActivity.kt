package dev.tanuj.coachcompass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val Bg = Color(0xFF0F0B1E)
    val Card = Color(0xFF1B1530)
    val Card2 = Color(0xFF261E42)
    val Purple = Color(0xFFA46CF0)
    val Green = Color(0xFF3DDC97)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF5A5F)
    val Text = Color(0xFFF3F0FA)
    val Muted = Color(0xFFA49DBA)
}

fun crowdColor(load: Int) = when (crowdOf(load)) {
    Crowd.EMPTY -> C.Green
    Crowd.MEDIUM -> C.Amber
    Crowd.FULL -> C.Red
}

fun mmss(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

enum class Screen { HOME, SENSOR, PLATFORM, GUIDE, DOOR_SETUP, DOOR }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        Repo.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = C.Purple, background = C.Bg, surface = C.Card)) {
                App()
            }
        }
    }
}

@Composable
fun App() {
    LaunchedEffect(Unit) { Repo.pollForever() }

    var screen by remember { mutableStateOf(Screen.HOME) }
    // Where the rider is standing on the platform; the story starts Ananya at 3-B.
    var userCoach by remember { mutableIntStateOf(3) }
    var userDoor by remember { mutableIntStateOf(1) }
    var guideTo by remember { mutableStateOf<Pick?>(null) }
    var doorCoach by remember { mutableIntStateOf(3) }

    BackHandler(enabled = screen != Screen.HOME) {
        screen = if (screen == Screen.GUIDE) Screen.PLATFORM else if (screen == Screen.DOOR) Screen.DOOR_SETUP else Screen.HOME
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        when (screen) {
            Screen.HOME -> HomeScreen { screen = it }
            Screen.SENSOR -> SensorScreen(onBack = { screen = Screen.HOME })
            Screen.PLATFORM -> PlatformScreen(
                userCoach, userDoor,
                onStand = { c, d -> userCoach = c; userDoor = d },
                onGuide = { guideTo = it; screen = Screen.GUIDE },
                onBack = { screen = Screen.HOME },
            )
            Screen.GUIDE -> GuideScreen(userCoach, guideTo!!) { arrived ->
                if (arrived) userCoach = guideTo!!.coach.n
                screen = Screen.PLATFORM
            }
            Screen.DOOR_SETUP -> DoorSetupScreen(doorCoach, { doorCoach = it }, { screen = Screen.DOOR }, { screen = Screen.HOME })
            Screen.DOOR -> DoorLightScreen(doorCoach) { screen = Screen.DOOR_SETUP }
        }
    }
}

// ---------- shared pieces ----------

@Composable
fun Header(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(C.Card2).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.size(12.dp))
        }
        Column {
            Text(title, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = C.Muted, fontSize = 13.sp)
        }
    }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, color: Color = C.Card, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color).padding(16.dp)) { content() }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) C.Purple else C.Card2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) Color.Black else C.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, color: Color = C.Purple, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (color.luminance() > 0.2f) Color.Black else C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun LiveDot(on: Boolean) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(if (on) C.Green else C.Amber))
}

/** The six coaches, front (Coach 1) on the left. Each bar fills to its load. */
@Composable
fun CoachStrip(
    coaches: List<Coach>,
    highlight: Int? = null,
    you: Int? = null,
    onTap: ((Int) -> Unit)? = null,
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            coaches.forEach { c ->
                val col = crowdColor(c.load)
                Column(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .then(if (highlight == c.n) Modifier.border(3.dp, C.Text, RoundedCornerShape(10.dp)) else Modifier)
                        .background(C.Card2)
                        .then(if (onTap != null) Modifier.clickable { onTap(c.n) } else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(c.load / 100f).background(col.copy(alpha = 0.85f)))
                        Column(Modifier.fillMaxSize().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${c.load}%", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            if (c.source == "live") Text("LIVE", color = C.Text, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        if (c.womenOnly) "C${c.n} ♀" else "C${c.n}",
                        color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
        if (you != null) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..Platform.COACHES).forEach { n ->
                    Text(
                        if (n == you) "▲ You" else "",
                        color = C.Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text("◀ Front", color = C.Muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("Rear", color = C.Muted, fontSize = 11.sp)
        }
    }
}
