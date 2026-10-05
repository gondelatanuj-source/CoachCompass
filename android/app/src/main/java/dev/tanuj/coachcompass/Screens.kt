package dev.tanuj.coachcompass

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

// ---------- Home ----------

@Composable
fun HomeScreen(go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(Repo.serverUrl) }
    val s = Repo.state
    Page {
        Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Text("CoachCompass", color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("Find the empty coach before the train arrives", color = C.Muted, fontSize = 14.sp)
        }

        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(Repo.connected)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (Repo.connected) "Live: connected to ${Repo.serverUrl.substringAfter("://")}"
                        else if (Repo.serverUrl.isEmpty()) "Demo mode: simulated train (no server)"
                        else "Can't reach server, showing simulated train",
                        color = C.Text, fontSize = 13.sp,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        placeholder = { Text("Laptop IP, e.g. 192.168.1.5") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.Text, unfocusedTextColor = C.Text),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    Chip("Save", false) { Repo.setServer(ctx, url); url = Repo.serverUrl }
                }
                Text("Phone ID ${Repo.phoneId} · ${s.train} to ${s.towards} · ${s.station} P${s.platform}", color = C.Muted, fontSize = 12.sp)
            }
        }

        ModeCard("On the train", "Sense my coach with Bluetooth and share one anonymous number", C.Purple) { go(Screen.SENSOR) }
        ModeCard("On the platform", "See every coach of the next train and walk to the emptiest door", C.Green) { go(Screen.PLATFORM) }
        ModeCard("Door light (no-app mode)", "Turn this phone into the red/green light above a platform door", C.Amber) { go(Screen.DOOR_SETUP) }

        Text(
            "Prototype for the iQOO Hackathon · Track 07 Mobility. Platform guidance uses a simulated walk; Bluetooth counts are real.",
            color = C.Muted, fontSize = 11.sp,
        )
    }
}

@Composable
private fun ModeCard(title: String, body: String, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Card).clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 6.dp, height = 48.dp).clip(RoundedCornerShape(3.dp)).background(accent))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(body, color = C.Muted, fontSize = 13.sp)
        }
        Text("›", color = accent, fontSize = 30.sp)
    }
}

// ---------- On the train: Bluetooth sensor ----------

@Composable
fun SensorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val counter = remember { BleCounter(ctx) }
    var coach by remember { mutableIntStateOf(4) }
    var sensing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var counts by remember { mutableStateOf(BleCounter.Counts(0, 0, 0, 0)) }
    var lastUpload by remember { mutableLongStateOf(0L) }
    var lastOk by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { error = counter.start(); sensing = error == null } else error = "Bluetooth permission is needed to count nearby phones"
    }

    DisposableEffect(Unit) { onDispose { counter.stop() } }

    LaunchedEffect(sensing, coach) {
        var tick = 0
        while (sensing) {
            counts = counter.counts()
            now = System.currentTimeMillis()
            // Wait for the first 5 s scan window to fill before uploading.
            if (tick > 0 && tick % 5 == 0) {
                val people = (Platform.peopleFromDevices(counts.total) * scale).roundToInt()
                lastOk = Repo.report(coach, counts.total, people, Platform.loadFromPeople(people))
                lastUpload = System.currentTimeMillis()
            }
            tick++
            delay(1000)
        }
    }

    val people = (Platform.peopleFromDevices(counts.total) * scale).roundToInt()
    val load = Platform.loadFromPeople(people)

    Page {
        Header("On the train", "${Repo.state.train} · Purple Line to ${Repo.state.towards}", onBack)

        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Which coach are you in?", color = C.Text, fontWeight = FontWeight.SemiBold)
                Text("In the full app you scan the QR sticker by the door.", color = C.Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..Platform.COACHES).forEach { n -> Chip("$n", coach == n, Modifier.weight(1f)) { coach = n } }
                }
            }
        }

        CardBox(color = if (sensing) C.Card2 else C.Card) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Coach $coach", color = C.Muted)
                Text("$load%", color = crowdColor(load), fontSize = 64.sp, fontWeight = FontWeight.ExtraBold)
                Text("≈ $people people · ${counts.total} Bluetooth devices", color = C.Text)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat("Near", counts.near, Modifier.weight(1f))
                    Stat("Mid", counts.mid, Modifier.weight(1f))
                    Stat("Far", counts.far, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        !sensing -> "Not sensing"
                        lastUpload == 0L -> "Starting…"
                        lastOk -> "Sent to server ${(now - lastUpload) / 1000}s ago"
                        else -> "Offline: shown on this phone's platform view only"
                    },
                    color = if (lastOk) C.Green else C.Muted, fontSize = 13.sp,
                )
            }
        }

        if (error != null) Text(error!!, color = C.Red, fontSize = 14.sp)

        BigButton(if (sensing) "Stop sensing" else "Start sensing", color = if (sensing) C.Red else C.Purple) {
            if (sensing) { counter.stop(); sensing = false; counts = BleCounter.Counts(0, 0, 0, 0) }
            else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                error = counter.start(); sensing = error == null
            } else permission.launch(Manifest.permission.BLUETOOTH_SCAN)
        }

        CardBox {
            Column {
                Text("Demo scale ×${scale.roundToInt()}", color = C.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    "A hackathon hall has far fewer phones than a rush-hour coach. Scale the real count up to show a full coach. Set ×1 for raw numbers.",
                    color = C.Muted, fontSize = 12.sp,
                )
                Slider(
                    value = scale, onValueChange = { scale = it }, valueRange = 1f..10f, steps = 8,
                    colors = SliderDefaults.colors(thumbColor = C.Purple, activeTrackColor = C.Purple),
                )
            }
        }

        Text(
            "Privacy: device addresses stay in memory for 15 seconds and never leave the phone. Only the coach number and a head count are sent. " +
                "Estimate = devices ÷ ${Platform.DEVICES_PER_PERSON} per person; ${Platform.FULL_COACH_PEOPLE} people = 100%.",
            color = C.Muted, fontSize = 11.sp,
        )
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = C.Muted, fontSize = 11.sp)
    }
}

// ---------- On the platform ----------

@Composable
fun PlatformScreen(userCoach: Int, userDoor: Int, onStand: (Int, Int) -> Unit, onGuide: (Pick) -> Unit, onBack: () -> Unit) {
    var womenOnly by remember { mutableStateOf(false) }
    val s = Repo.state
    val picks = Platform.recommend(s, userCoach, userDoor, womenOnly)
    val best = picks.first()
    val emptier = picks.drop(1).firstOrNull { it.coach.load < best.coach.load }

    Page {
        Header("${s.station} · Platform ${s.platform}", "Purple Line towards ${s.towards}", onBack)

        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Next train ${s.train}", color = C.Muted, fontSize = 13.sp)
                    Text(
                        if (s.phase == "boarding") "Boarding now" else "Arrives in ${mmss(s.etaSec)}",
                        color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    )
                }
                LiveDot(Repo.connected)
                Spacer(Modifier.size(6.dp))
                Text(if (Repo.connected) "Live" else "Demo", color = C.Muted, fontSize = 12.sp)
            }
        }

        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tap the coach you're standing at", color = C.Muted, fontSize = 12.sp)
                CoachStrip(s.coaches, highlight = best.coach.n, you = userCoach) { onStand(it, userDoor) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Door", color = C.Muted, fontSize = 13.sp)
                    Platform.DOORS.forEachIndexed { i, ch -> Chip("$userCoach-$ch", userDoor == i) { onStand(userCoach, i) } }
                }
            }
        }

        CardBox(color = C.Card2) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("BEST FOR YOU", color = C.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Coach ${best.coach.n} · door ${best.doorLabel}", color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "${best.coach.load}% full · ${crowdOf(best.coach.load).label.lowercase()} · " +
                        if (best.distM < 1) "you're already here" else "${best.distM.roundToInt()} m, about ${best.walkSec}s walk",
                    color = C.Text, fontSize = 15.sp,
                )
                if (emptier != null) {
                    Text(
                        "Coach ${emptier.coach.n} is emptier (${emptier.coach.load}%) but ${emptier.distM.roundToInt()} m away.",
                        color = C.Muted, fontSize = 13.sp,
                    )
                }
                if (!best.reachable) Text("Hurry: the doors may close before you get there.", color = C.Amber, fontSize = 13.sp)
            }
        }

        if (best.distM >= 1) BigButton("Guide me to ${best.doorLabel}", color = C.Green) { onGuide(best) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Include women-only coach (C1)", color = C.Text, fontSize = 14.sp)
                Text("Coach 1 is reserved for women", color = C.Muted, fontSize = 12.sp)
            }
            Switch(checked = womenOnly, onCheckedChange = { womenOnly = it })
        }
    }
}

// ---------- Guide me ----------

@Composable
fun GuideScreen(fromCoach: Int, target: Pick, onDone: (arrived: Boolean) -> Unit) {
    val ctx = LocalContext.current
    var left by remember { mutableDoubleStateOf(target.distM) }
    var paused by remember { mutableStateOf(false) }
    val arrived = left <= 0.0
    val towardRear = target.coach.n > fromCoach

    LaunchedEffect(paused, arrived) {
        while (!paused && !arrived) {
            delay(100)
            left = max(0.0, left - Platform.WALK_MPS * 0.1)
        }
    }
    LaunchedEffect(arrived) { if (arrived) buzzTwice(ctx) }

    Column(
        Modifier.fillMaxSize().background(if (arrived) C.Green else C.Bg).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (arrived) "You're at ${target.doorLabel}" else "Walk to door ${target.doorLabel}",
            color = if (arrived) Color.Black else C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Text(
            if (arrived) "Stand behind the yellow line. Coach ${target.coach.n} is ${target.coach.load}% full."
            else "Towards the ${if (towardRear) "rear" else "front"} of the platform",
            color = if (arrived) Color.Black else C.Muted, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Arrow(arrived, towardRear)
        Spacer(Modifier.height(16.dp))
        if (!arrived) Text("${left.roundToInt()} m", color = C.Text, fontSize = 72.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        val s = Repo.state
        Text(
            if (s.phase == "boarding") "${s.train} is boarding" else "${s.train} arrives in ${mmss(s.etaSec)}",
            color = if (arrived) Color.Black else C.Muted, fontSize = 15.sp,
        )
        Spacer(Modifier.height(16.dp))
        if (arrived) {
            BigButton("Done", color = Color.White.copy(alpha = 0.5f)) { onDone(true) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(if (paused) "Resume" else "Pause", Modifier.weight(1f), C.Card2) { paused = !paused }
                BigButton("I'm here", Modifier.weight(1f), C.Green) { left = 0.0 }
            }
        }
        Text(
            "Prototype: the walk is simulated at ${Platform.WALK_MPS} m/s. Indoor positioning comes from the door-marker beacons in the full app.",
            color = if (arrived) Color.Black.copy(alpha = 0.6f) else C.Muted, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun Arrow(done: Boolean, right: Boolean) {
    val color = if (done) Color.Black else C.Green
    Canvas(Modifier.size(200.dp).rotate(if (right) 0f else 180f)) {
        val w = size.width
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        if (done) {
            val p = Path().apply { moveTo(w * 0.2f, w * 0.52f); lineTo(w * 0.42f, w * 0.74f); lineTo(w * 0.82f, w * 0.3f) }
            drawPath(p, color, style = stroke)
        } else {
            drawLine(color, Offset(w * 0.12f, w / 2), Offset(w * 0.84f, w / 2), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            val p = Path().apply { moveTo(w * 0.55f, w * 0.22f); lineTo(w * 0.86f, w / 2); lineTo(w * 0.55f, w * 0.78f) }
            drawPath(p, color, style = stroke)
        }
    }
}

private fun buzzTwice(ctx: Context) {
    val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager ?: return
    vm.vibrate(CombinedVibration.createParallel(VibrationEffect.createWaveform(longArrayOf(0, 180, 140, 180), -1)))
}

// ---------- Door light (no-app mode) ----------

@Composable
fun DoorSetupScreen(coach: Int, onCoach: (Int) -> Unit, onStart: () -> Unit, onBack: () -> Unit) {
    Page {
        Header("Door light", "No-app mode for riders without a phone", onBack)
        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This phone shows the light above the door for coach…", color = C.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..Platform.COACHES).forEach { n -> Chip("$n", coach == n, Modifier.weight(1f)) { onCoach(n) } }
                }
                Text(
                    "Line up one phone per coach along the table. Green = board here, amber = some space, red = full (it points to a better coach).",
                    color = C.Muted, fontSize = 13.sp,
                )
            }
        }
        CoachStrip(Repo.state.coaches, highlight = coach)
        BigButton("Start door light", color = C.Green, onClick = onStart)
        Text("Tap the light to go back.", color = C.Muted, fontSize = 12.sp)
    }
}

@Composable
fun DoorLightScreen(coach: Int, onExit: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val ctl = WindowCompat.getInsetsController(window, view)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        val attrs = window.attributes
        attrs.screenBrightness = 1f
        window.attributes = attrs
        onDispose {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
            window.attributes = window.attributes.also { it.screenBrightness = -1f }
        }
    }

    val s = Repo.state
    val c = s.coaches.first { it.n == coach }
    val crowd = crowdOf(c.load)
    val bg = crowdColor(c.load)
    // Suggest the emptiest non-women-only coach a rider here could still walk to.
    val better = Platform.recommend(s, coach, 1, allowWomenOnly = false).first().coach

    Column(
        Modifier.fillMaxSize().background(bg).clickable(onClick = onExit).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("COACH ${c.n}${if (c.womenOnly) " · WOMEN" else ""}", color = Color.Black, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("${c.load}%", color = Color.Black, fontSize = 120.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            when (crowd) {
                Crowd.EMPTY -> "BOARD HERE"
                Crowd.MEDIUM -> "SOME SPACE"
                Crowd.FULL -> "FULL"
            },
            color = Color.Black, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
        )
        if (crowd == Crowd.FULL && better.n != c.n) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Try coach ${better.n} ${if (better.n > c.n) "→" else "←"}",
                color = Color.Black, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(30.dp))
        Text(
            if (s.phase == "boarding") "${s.train} boarding" else "${s.train} in ${mmss(s.etaSec)}",
            color = Color.Black.copy(alpha = 0.7f), fontSize = 20.sp,
        )
    }
}
