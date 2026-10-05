package dev.tanuj.coachcompass

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Single source of truth for the screens. With a server URL set it polls the laptop server every
 * second; without one (or when the server is unreachable) it falls back to [Sim], so one phone can
 * demo everything on its own.
 */
object Repo {
    var state by mutableStateOf(Sim.state(System.currentTimeMillis(), emptyMap()))
        private set
    var connected by mutableStateOf(false)
        private set
    var serverUrl by mutableStateOf("")
        private set

    lateinit var phoneId: String
        private set

    /** Reports this phone made while offline: coach -> (load, time), so the sensor still drives the platform view. */
    private val localReports = mutableMapOf<Int, Pair<Int, Long>>()

    fun init(ctx: Context) {
        val prefs = ctx.getSharedPreferences("cc", Context.MODE_PRIVATE)
        serverUrl = prefs.getString("server", "") ?: ""
        phoneId = prefs.getString("phone", null) ?: UUID.randomUUID().toString().take(6).also {
            prefs.edit().putString("phone", it).apply()
        }
    }

    fun setServer(ctx: Context, url: String) {
        var u = url.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http")) u = "http://$u"
        if (u.isNotEmpty() && u.substringAfter("://").count { it == ':' } == 0) u = "$u:8080"
        serverUrl = u
        connected = false
        ctx.getSharedPreferences("cc", Context.MODE_PRIVATE).edit().putString("server", u).apply()
    }

    suspend fun pollForever() {
        while (true) {
            val url = serverUrl
            val remote = if (url.isEmpty()) null else runCatching { fetch(url) }.getOrNull()
            connected = remote != null
            state = remote ?: Sim.state(System.currentTimeMillis(), freshLocal())
            delay(1000)
        }
    }

    /** Sends one anonymous number per coach. Returns true when the server accepted it. */
    suspend fun report(coach: Int, devices: Int, people: Int, load: Int): Boolean {
        localReports[coach] = load to System.currentTimeMillis()
        val url = serverUrl
        if (url.isEmpty()) return false
        return runCatching {
            val body = JSONObject()
                .put("coach", coach).put("devices", devices).put("people", people)
                .put("load", load).put("phoneId", phoneId)
            post("$url/api/report", body.toString())
        }.getOrDefault(false)
    }

    private fun freshLocal(): Map<Int, Int> {
        val now = System.currentTimeMillis()
        return localReports.filterValues { now - it.second < 20_000 }.mapValues { it.value.first }
    }

    private suspend fun fetch(base: String): TrainState = withContext(Dispatchers.IO) {
        val conn = (URL("$base/api/state").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1500; readTimeout = 1500
        }
        try {
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = j.getJSONArray("coaches")
            TrainState(
                train = j.optString("train", "TR-14"),
                station = j.optString("station", "Trinity"),
                platform = j.optInt("platform", 2),
                towards = j.optString("towards", "Whitefield"),
                etaSec = j.optInt("etaSec", 120),
                phase = j.optString("phase", "approaching"),
                coaches = (0 until arr.length()).map {
                    val c = arr.getJSONObject(it)
                    Coach(c.getInt("n"), c.getInt("load"), c.optString("source", "model"), c.optInt("phones", 0))
                },
            )
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun post(url: String, json: String): Boolean = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 1500; readTimeout = 1500
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray()) }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }
}
