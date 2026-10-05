package dev.tanuj.coachcompass

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** One coach as the platform sees it. `source` is "live" when phones on board reported it recently. */
data class Coach(val n: Int, val load: Int, val source: String = "model", val phones: Int = 0) {
    val womenOnly get() = n == 1
}

data class TrainState(
    val train: String = "TR-14",
    val station: String = "Trinity",
    val platform: Int = 2,
    val towards: String = "Whitefield",
    val etaSec: Int = 120,
    val phase: String = "approaching", // approaching | boarding
    val coaches: List<Coach>,
)

data class Pick(val coach: Coach, val door: Int, val distM: Double, val walkSec: Int, val score: Double, val reachable: Boolean) {
    val doorLabel get() = "${coach.n}-${Platform.DOORS[door]}"
}

object Platform {
    const val COACHES = 6
    const val COACH_LEN_M = 21.0
    const val DOORS = "ABCD"
    const val WALK_MPS = 1.3

    /** Calibration from the pilot plan: 0.85 Bluetooth devices per person, 330 people = 100 % coach. */
    const val DEVICES_PER_PERSON = 0.85
    const val FULL_COACH_PEOPLE = 330

    fun doorPos(coach: Int, door: Int) = (coach - 1) * COACH_LEN_M + 2.6 + door * 5.27

    fun peopleFromDevices(devices: Int) = (devices / DEVICES_PER_PERSON).roundToInt()
    fun loadFromPeople(people: Int) = (people * 100 / FULL_COACH_PEOPLE).coerceIn(0, 100)

    /**
     * Ranks every coach for a rider standing at [userCoach]-[userDoor]. The rider keeps the same door
     * letter, so walking distance is whole coach lengths. Score = load % + 0.6 per metre walked;
     * coaches the rider can't reach before the doors close drop to the bottom.
     */
    fun recommend(state: TrainState, userCoach: Int, userDoor: Int, allowWomenOnly: Boolean): List<Pick> {
        val from = doorPos(userCoach, userDoor)
        val timeLeft = if (state.phase == "boarding") 20 else state.etaSec + 20
        return state.coaches
            .filter { allowWomenOnly || !it.womenOnly }
            .map { c ->
                val dist = abs(doorPos(c.n, userDoor) - from)
                val walk = (dist / WALK_MPS).roundToInt()
                val reachable = walk + 5 <= timeLeft
                Pick(c, userDoor, dist, walk, c.load + 0.6 * dist + if (reachable) 0.0 else 1000.0, reachable)
            }
            .sortedBy { it.score }
    }
}

enum class Crowd(val label: String) { EMPTY("Plenty of space"), MEDIUM("Some space"), FULL("Crowded") }

fun crowdOf(load: Int) = when {
    load < 50 -> Crowd.EMPTY
    load < 75 -> Crowd.MEDIUM
    else -> Crowd.FULL
}

/** Offline stand-in for the server: the demo story's loads with a gentle drift and a 150 s train cycle. */
object Sim {
    private val BASE = intArrayOf(58, 88, 84, 63, 38, 29)
    private const val CYCLE = 170 // 150 s approach + 20 s boarding

    fun state(nowMs: Long, local: Map<Int, Int>): TrainState {
        val t = (nowMs / 1000) % CYCLE
        val boarding = t >= 150
        val coaches = (1..Platform.COACHES).map { n ->
            local[n]?.let { Coach(n, it, "live", 1) }
                ?: Coach(n, (BASE[n - 1] + 1.5 * sin(nowMs / 7000.0 + n)).roundToInt().coerceIn(0, 100))
        }
        return TrainState(
            etaSec = if (boarding) 0 else (150 - t).toInt(),
            phase = if (boarding) "boarding" else "approaching",
            coaches = coaches,
        )
    }
}
