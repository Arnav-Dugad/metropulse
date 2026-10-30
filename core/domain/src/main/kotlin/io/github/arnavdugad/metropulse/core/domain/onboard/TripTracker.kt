package io.github.arnavdugad.metropulse.core.domain.onboard

import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.geo.haversineM
import io.github.arnavdugad.metropulse.core.domain.routing.Journey
import io.github.arnavdugad.metropulse.core.domain.routing.Leg
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/** Where a position comes from; the UI labels it so a guess is never shown as a measurement. */
enum class PositionSource {
    /** From the phone's GPS within the last few seconds. */
    Live,

    /** Moved along by the operator's timetable since the last fix (or since departure). */
    Scheduled,

    /** Moved along by modelled or estimated run times. */
    Approximate,
}

enum class TripPhase { WaitingToBoard, Riding, Arrived }

data class TripProgress(
    val phase: TripPhase,
    val rideIndex: Int,
    val ride: Leg.Ride,
    /** Position along the current ride's pattern, in metres. */
    val alongM: Double,
    /** Pattern index of the next stop ahead (or the stop the train is at). */
    val nextStopIndex: Int,
    /** Stops left on this ride, counting the one to get off at. */
    val stopsToAlight: Int,
    /** Stops left on the whole journey. */
    val stopsToDestination: Int,
    /** Null when there is nothing to base it on (untimed journey before boarding). */
    val secondsToDestination: Int?,
    /** Share of the journey's ride distance covered, 0..1. */
    val fraction: Double,
    val source: PositionSource,
    val speedMps: Double?,
    /** The rider is on the line but going the other way. */
    val wrongDirection: Boolean,
    /** The train has gone past the stop to get off at. */
    val missedStop: Boolean,
    /** Becomes true once, when the wake alert should go off; stays true afterwards. */
    val alertDue: Boolean,
)

/**
 * Follows a rider through a planned journey: waiting at the platform, riding (from GPS, or by the
 * timetable while underground), changing trains, and arriving. Raises the wake alert [alertStops]
 * stops before the destination, or [alertLeadSecs] before it if that comes first.
 */
class TripTracker(
    private val network: TransitNetwork,
    val journey: Journey,
    private val alertStops: Int = 1,
    private val alertLeadSecs: Int = 90,
) {
    private val zone: ZoneId = network.timezone
    private val serviceDate: LocalDate = journey.serviceDate
    private val rides = journey.rides
    private val totalRideM = rides.sumOf { it.distanceM }.coerceAtLeast(1.0)

    private var rideIndex = 0
    private var phase = TripPhase.WaitingToBoard
    private var alertFired = false

    // Last accepted GPS position on the current ride.
    private var anchorAlong: Double? = null
    private var anchorMillis = 0L
    private var lastFixMillis: Long? = null
    /** Any fix at all, on this line or not: GPS is working, so the schedule is not assumed. */
    private var lastAnyFixMillis: Long? = null
    private var speed: Double? = null
    private var wrongDirection = false
    private var stoppedSinceMillis: Long? = null

    private var matcher = matcherFor(0)
    private var reckoner = reckonerFor(0)

    var progress: TripProgress = snapshot(System.currentTimeMillis(), rides.first().stopDistance(rides.first().boardIndex))
        private set

    private fun Leg.Ride.stopDistance(index: Int) = network.patternById.getValue(patternId).stopDistM[index]

    private fun matcherFor(i: Int): MapMatcher {
        val ride = rides[i]
        val own = network.patternById.getValue(ride.patternId)
        // The ride's own pattern plus the line's patterns running the other way, so a wrong-way train
        // is noticed. Same-way patterns (short turns, longer runs) are left out: on shared track they
        // match equally well and would hide the ride.
        val opposite = network.patterns.filter { it.lineId == ride.lineId && it.direction != own.direction }
        return MapMatcher(listOf(own) + opposite)
    }

    private fun reckonerFor(i: Int): DeadReckoner {
        val ride = rides[i]
        val pattern = network.patternById.getValue(ride.patternId)
        val profile = ride.tripId?.let { id -> network.trips.firstOrNull { it.id == id } }?.let { network.profileById[it.profileId] }
            ?: network.profiles.firstOrNull { it.patternId == pattern.id }
        return DeadReckoner(pattern, profile)
    }

    fun onFix(fix: GpsFix): TripProgress {
        if (phase == TripPhase.Arrived) return progress
        val ride = rides[rideIndex]
        lastAnyFixMillis = fix.timeMillis
        val state = matcher.update(fix)
        if (state != null) {
            val onRide = state.patternId == ride.patternId
            wrongDirection = !onRide && state.confidence >= 0.5 && state.speedMps > MOVING_MPS
            if (onRide) {
                anchorAlong = state.alongM
                anchorMillis = fix.timeMillis
                lastFixMillis = fix.timeMillis
                speed = state.speedMps
                val boardM = ride.stopDistance(ride.boardIndex)
                if (phase == TripPhase.WaitingToBoard && state.speedMps > MOVING_MPS && state.alongM > boardM + BOARDED_PAST_M) {
                    phase = TripPhase.Riding
                }
                stoppedSinceMillis = if (state.speedMps < STOPPED_MPS) (stoppedSinceMillis ?: fix.timeMillis) else null
            }
        }
        // Standing on the platform: the direction cannot be told yet, but the position is known.
        val boardStation = network.stationById[ride.fromStationId]
        if (phase == TripPhase.WaitingToBoard && lastFixMillis != fix.timeMillis && boardStation != null &&
            haversineM(fix.location, boardStation.location) <= AT_PLATFORM_M
        ) {
            anchorAlong = ride.stopDistance(ride.boardIndex)
            anchorMillis = fix.timeMillis
            lastFixMillis = fix.timeMillis
            speed = 0.0
        }
        return update(fix.timeMillis)
    }

    fun onTick(timeMillis: Long): TripProgress = if (phase == TripPhase.Arrived) progress else update(timeMillis)

    private fun update(now: Long): TripProgress {
        var ride = rides[rideIndex]
        val live = isLive(now)
        val gpsWorking = lastAnyFixMillis?.let { now - it <= LIVE_WINDOW_MS } == true
        val along: Double = when {
            live -> anchorAlong!! + (speed ?: 0.0) * (now - anchorMillis) / 1000.0
            anchorAlong != null && phase == TripPhase.Riding -> reckoner.advance(anchorAlong!!, (now - anchorMillis) / 1000.0)
            !gpsWorking && phase == TripPhase.WaitingToBoard && ride.departSecs != null && secsOfDay(now) >= ride.departSecs + SCHEDULE_GRACE_SECS -> {
                // Underground platforms: no fix yet, but the timetabled train has left; assume the rider is on it.
                phase = TripPhase.Riding
                reckoner.advance(ride.stopDistance(ride.boardIndex), secsOfDay(now) - ride.departSecs)
            }
            phase == TripPhase.Riding && ride.departSecs != null -> reckoner.advance(ride.stopDistance(ride.boardIndex), secsOfDay(now) - ride.departSecs)
            else -> ride.stopDistance(ride.boardIndex)
        }.let { a -> a.coerceIn(0.0, network.patternById.getValue(ride.patternId).shape.lengthM) }

        val alightM = ride.stopDistance(ride.alightIndex)
        val stopped = stoppedSinceMillis?.let { now - it >= STOPPED_FOR_MS } == true
        // With GPS the train must have stopped at the platform; without it, the schedule says it has arrived.
        val arrivedHere = phase == TripPhase.Riding && along >= alightM - AT_STOP_M && along <= alightM + PAST_STOP_M && (stopped || !live)
        if (arrivedHere) {
            if (rideIndex == rides.lastIndex) {
                phase = TripPhase.Arrived
            } else {
                rideIndex++
                phase = TripPhase.WaitingToBoard
                matcher = matcherFor(rideIndex)
                reckoner = reckonerFor(rideIndex)
                anchorAlong = null
                lastFixMillis = null
                speed = null
                stoppedSinceMillis = null
                wrongDirection = false
                ride = rides[rideIndex]
                progress = snapshot(now, ride.stopDistance(ride.boardIndex))
                return progress
            }
        }
        progress = snapshot(now, along)
        return progress
    }

    private fun snapshot(now: Long, along: Double): TripProgress {
        val ride = rides[rideIndex]
        val pattern = network.patternById.getValue(ride.patternId)
        val next = (ride.boardIndex..ride.alightIndex).firstOrNull { pattern.stopDistM[it] + AT_STOP_M >= along } ?: ride.alightIndex
        val stopsToAlight = if (phase == TripPhase.WaitingToBoard) ride.stopCount else max(0, ride.alightIndex - next + 1)
        val later = rides.drop(rideIndex + 1)
        val stopsToDestination = if (phase == TripPhase.Arrived) 0 else stopsToAlight + later.sumOf { it.stopCount }

        val secs = remainingSeconds(now, ride, along)
        val doneM = rides.take(rideIndex).sumOf { it.distanceM } + (along - pattern.stopDistM[ride.boardIndex]).coerceIn(0.0, ride.distanceM)
        val live = isLive(now)
        val quality = network.lineById[ride.lineId]?.quality
        val source = when {
            live -> PositionSource.Live
            journey.timed && quality != DataQuality.Approximate -> PositionSource.Scheduled
            else -> PositionSource.Approximate
        }
        val lastRide = rideIndex == rides.lastIndex
        if (!alertFired && phase == TripPhase.Riding && lastRide &&
            (stopsToAlight <= alertStops || (secs != null && secs <= alertLeadSecs))
        ) {
            alertFired = true
        }
        return TripProgress(
            phase = phase, rideIndex = rideIndex, ride = ride, alongM = along, nextStopIndex = next,
            stopsToAlight = stopsToAlight, stopsToDestination = stopsToDestination,
            secondsToDestination = if (phase == TripPhase.Arrived) 0 else secs,
            fraction = if (phase == TripPhase.Arrived) 1.0 else (doneM / totalRideM).coerceIn(0.0, 1.0),
            source = source, speedMps = if (live) speed else null,
            wrongDirection = wrongDirection,
            missedStop = phase == TripPhase.Riding && along > pattern.stopDistM[ride.alightIndex] + MISSED_STOP_M,
            alertDue = alertFired,
        )
    }

    /** Time to the destination: this ride by run times from the current position, later legs as planned. */
    private fun remainingSeconds(now: Long, ride: Leg.Ride, along: Double): Int? {
        val nowSecs = secsOfDay(now)
        val thisRide = when (phase) {
            TripPhase.WaitingToBoard -> {
                val wait = ride.departSecs?.let { (it - nowSecs).coerceAtLeast(0.0) }
                if (wait == null && !journey.timed) null else (wait ?: 0.0) + ride.durationSecs
            }
            else -> reckoner.secondsToStop(along, ride.alightIndex)
        } ?: return null
        var total = thisRide
        var prevArrive = ride.arriveSecs
        for (leg in journey.legs.drop(journey.legs.indexOf(ride) + 1)) {
            if (journey.timed) {
                // The planned gap to the next train already includes walking between platforms.
                if (leg is Leg.Ride) {
                    total += (leg.departSecs!! - prevArrive!!) + leg.durationSecs
                    prevArrive = leg.arriveSecs
                }
            } else {
                total += leg.durationSecs
            }
        }
        return total.toInt()
    }

    private fun isLive(now: Long) = lastFixMillis?.let { now - it <= LIVE_WINDOW_MS } == true

    private fun secsOfDay(millis: Long): Double {
        val midnight = serviceDate.atStartOfDay(zone).toInstant().toEpochMilli()
        return (millis - midnight) / 1000.0
    }

    private companion object {
        const val LIVE_WINDOW_MS = 20_000L
        const val MOVING_MPS = 3.0
        const val STOPPED_MPS = 1.0
        const val STOPPED_FOR_MS = 8_000L
        const val BOARDED_PAST_M = 60.0
        const val AT_STOP_M = 40.0
        const val AT_PLATFORM_M = 150.0
        const val PAST_STOP_M = 150.0
        const val MISSED_STOP_M = 400.0
        const val SCHEDULE_GRACE_SECS = 30
    }
}

/** Epoch millis of [secs] after [date]'s midnight in [zone]. */
fun epochMillisOf(date: LocalDate, secs: Int, zone: ZoneId): Long =
    Instant.from(date.atStartOfDay(zone)).toEpochMilli() + secs * 1000L
