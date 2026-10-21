package ar.nahuel.bitacora.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {

    private val zone = ZoneId.of("America/Argentina/Catamarca")
    private val today = LocalDate.of(2026, 10, 15)
    private val engine = RecommendationEngine()

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant =
        date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()

    private fun walk(date: LocalDate, minutes: Long, hr: Long? = 120, kind: ExerciseKind = ExerciseKind.WALKING) =
        ExerciseSession(
            id = "s-$date-$minutes-$kind",
            start = at(date, 18),
            end = at(date, 18).plusSeconds(minutes * 60),
            type = 79,
            kind = kind,
            title = null,
            distanceMeters = null,
            avgHeartRate = hr,
            maxHeartRate = null,
            kilocalories = null,
            sourceApp = "com.sec.android.app.shealth",
        )

    private fun night(wake: LocalDate, asleepMinutes: Long, wakeHour: Int = 7, hr: Long? = 70): SleepSession {
        val end = at(wake, wakeHour)
        return SleepSession("n-$wake", end.minusSeconds(asleepMinutes * 60), end, 60, 80, asleepMinutes - 140, 0, hr)
    }

    private fun snapshot(
        days: List<DayActivity> = emptyList(),
        sessions: List<ExerciseSession> = emptyList(),
        sleeps: List<SleepSession> = emptyList(),
        weights: List<WeightEntry> = emptyList(),
    ) = HealthSnapshot(zone, days, sessions, sleeps, weights)

    private fun List<Recommendation>.byId(id: String) = firstOrNull { it.id == id }

    @Test
    fun flagsStepFloorWhenFourOrMoreDaysAreUnder() {
        val days = (1L..7L).map { offset ->
            DayActivity(today.minusDays(offset), if (offset <= 4) 2_000 else 12_000, null, null)
        }
        val rec = engine.evaluate(snapshot(days = days), today).byId("floor")
        assertEquals(Severity.ATTENTION, rec?.severity)
    }

    @Test
    fun praisesAFullWeekOverTheFloor() {
        val days = (1L..7L).map { DayActivity(today.minusDays(it), 8_000, null, null) }
        assertEquals(Severity.GOOD, engine.evaluate(snapshot(days = days), today).byId("floor")?.severity)
    }

    private fun activeDays(minutes: (Long) -> Long) =
        (0L..34L).map { DayActivity(today.minusDays(it), 8_000, null, null, activeMinutes = minutes(it)) }

    @Test
    fun alertsOnSteepLoadRamp() {
        val days = activeDays { if (it <= 6) 120 else 50 }
        val rec = engine.evaluate(snapshot(days = days), today).byId("ramp")
        assertEquals(Severity.ALERT, rec?.severity)
    }

    @Test
    fun doesNotFlagStableLoad() {
        assertNull(engine.evaluate(snapshot(days = activeDays { 60 }), today).byId("ramp"))
    }

    @Test
    fun activeMinutesUseStepBasedEstimate() {
        val snapshot = snapshot(days = activeDays { 30 })
        assertEquals(210L, snapshot.activeMinutes(today.minusDays(6), today.plusDays(1)))
    }

    @Test
    fun asksForStrengthWhenNoneInTwoWeeks() {
        val rec = engine.evaluate(snapshot(sessions = listOf(walk(today, 60))), today).byId("strength")
        assertEquals(Severity.ATTENTION, rec?.severity)
    }

    @Test
    fun strengthTargetMetSilencesRule() {
        val sessions = listOf(
            walk(today.minusDays(1), 40, kind = ExerciseKind.STRENGTH),
            walk(today.minusDays(4), 40, kind = ExerciseKind.STRENGTH),
        )
        assertNull(engine.evaluate(snapshot(sessions = sessions), today).byId("strength"))
    }

    @Test
    fun suggestsIntensityWhenAllSessionsAreEasy() {
        val rec = engine.evaluate(snapshot(sessions = listOf(walk(today, 60, hr = 115))), today).byId("intensity")
        assertEquals(Severity.INFO, rec?.severity)
    }

    @Test
    fun flagsShortSleep() {
        val sleeps = (0L..9L).map { night(today.minusDays(it), 330) }
        assertEquals(Severity.ALERT, engine.evaluate(snapshot(sleeps = sleeps), today).byId("sleep")?.severity)
    }

    @Test
    fun flagsIrregularWakeTime() {
        val sleeps = (0L..9L).map { night(today.minusDays(it), 450, wakeHour = if (it % 2 == 0L) 6 else 10) }
        assertTrue(engine.evaluate(snapshot(sleeps = sleeps), today).byId("schedule") != null)
    }

    @Test
    fun flagsWeekendShiftWithShortWorkweekSleep() {
        val sleeps = (0L..20L).map {
            val wake = today.minusDays(it)
            val weekend = wake.dayOfWeek.value >= 6
            night(wake, if (weekend) 480 else 360, wakeHour = if (weekend) 11 else 7)
        }
        val rec = engine.evaluate(snapshot(sleeps = sleeps), today).byId("schedule")
        assertEquals(Severity.ATTENTION, rec?.severity)
        assertTrue(rec!!.detail.contains("23:45"))
    }

    @Test
    fun steadyScheduleIsNotFlagged() {
        val sleeps = (0L..20L).map { night(today.minusDays(it), 450, wakeHour = 7) }
        assertNull(engine.evaluate(snapshot(sleeps = sleeps), today).byId("schedule"))
    }

    @Test
    fun flagsRisingNightHeartRate() {
        val baseline = (8L..40L).map { night(today.minusDays(it), 450, hr = 68) }
        val recent = (0L..5L).map { night(today.minusDays(it), 450, hr = 76) }
        val rec = engine.evaluate(snapshot(sleeps = baseline + recent), today).byId("night-hr")
        assertEquals(Severity.ATTENTION, rec?.severity)
    }

    @Test
    fun remindsToWeighInWhenStale() {
        val weights = listOf(WeightEntry("w1", at(today.minusDays(90), 8), 115.0))
        assertTrue(engine.evaluate(snapshot(weights = weights), today).byId("weight") != null)
    }

    @Test
    fun ordersBySeverity() {
        val sleeps = (0L..9L).map { night(today.minusDays(it), 330) }
        val result = engine.evaluate(snapshot(sleeps = sleeps, sessions = listOf(walk(today, 30))), today)
        assertEquals(result.sortedBy { it.severity.ordinal }, result)
    }

    @Test
    fun dashboardFillsMissingDaysWithZero() {
        val dashboard = DashboardBuilder.build(snapshot(days = listOf(DayActivity(today, 5_000, null, null))), today)
        assertEquals(14, dashboard.days.size)
        assertEquals(false, dashboard.days[1].hasData)
        assertEquals(5_000L, dashboard.days[0].steps)
    }
}

class TrendsBuilderTest {
    private val zone = ZoneId.of("America/Argentina/Catamarca")
    private val today = LocalDate.of(2026, 10, 15)

    private fun sleep(wake: LocalDate, minutes: Long, hr: Long = 70): SleepSession {
        val end = wake.atTime(7, 0).atZone(zone).toInstant()
        return SleepSession("n-$wake-$minutes", end.minusSeconds(minutes * 60), end, 60, 90, minutes - 150, 0, hr)
    }

    @Test
    fun napsAreExcludedFromMainSleeps() {
        val nap = SleepSession("nap", today.atTime(15, 0).atZone(zone).toInstant(), today.atTime(15, 40).atZone(zone).toInstant(), 0, 0, 40, 0, 80)
        val snapshot = HealthSnapshot(zone, emptyList(), emptyList(), listOf(sleep(today, 420), nap), emptyList())
        assertEquals(1, snapshot.mainSleeps.size)
    }

    @Test
    fun streakCountsConsecutiveDaysOverTarget() {
        val days = (0L..5L).map { DayActivity(today.minusDays(it), if (it <= 3) 9_000 else 2_000, null, null) }
        val trends = TrendsBuilder.build(HealthSnapshot(zone, days, emptyList(), emptyList(), emptyList()), today)
        assertEquals(4, trends.streakDays)
    }

    @Test
    fun weeklySeriesHasRequestedLength() {
        val trends = TrendsBuilder.build(HealthSnapshot.empty(zone), today)
        assertEquals(26, trends.weeklySteps.size)
        assertEquals(26, trends.weeklyLoad.size)
        assertEquals(7, trends.weekdayProfile.size)
    }

    @Test
    fun sleepWindowOffsetsAreRelativeToSixPm() {
        val trends = TrendsBuilder.build(HealthSnapshot(zone, emptyList(), emptyList(), listOf(sleep(today, 480)), emptyList()), today)
        val window = trends.sleepWindows.single()
        assertEquals(23L * 60 - 18 * 60, window.bedOffsetMinutes)
        assertEquals(13L * 60, window.wakeOffsetMinutes)
    }
}

class StrengthProgramTest {
    @Test
    fun weekNumberStartsAtOne() {
        val start = LocalDate.of(2026, 10, 16)
        assertEquals(1, StrengthProgram.weekNumber(start, start))
        assertEquals(2, StrengthProgram.weekNumber(start, start.plusDays(7)))
    }

    @Test
    fun thirdStrengthDayFromWeekSeven() {
        assertEquals(2, StrengthProgram.weekPlan(6).count { it.kind == DayKind.STRENGTH })
        assertEquals(3, StrengthProgram.weekPlan(7).count { it.kind == DayKind.STRENGTH })
    }

    @Test
    fun everyExerciseHasTwoFramesAndCues() {
        StrengthProgram.routines.flatMap { it.exercises }.forEach {
            assertEquals(it.id, 2, it.frames.size)
            assertTrue(it.id, it.cues.isNotEmpty() && it.avoid.isNotEmpty())
        }
    }

    @Test
    fun loggedRoutineSilencesStrengthNote() {
        val today = LocalDate.of(2026, 10, 15)
        val snapshot = HealthSnapshot.empty(ZoneId.of("America/Argentina/Catamarca"))
        val logged = listOf(today, today.minusDays(3))
        assertNull(RecommendationEngine().evaluate(snapshot, today, logged).firstOrNull { it.id == "strength" })
    }
}

class GeoTest {
    private fun walk(id: String, startMs: Long, metersPerSecond: Double, seconds: Int, lat0: Double = -28.47): Route {
        val dLat = metersPerSecond / 111_320.0
        val points = (0..seconds step 5).map { RoutePoint(startMs + it * 1000L, lat0 + dLat * it, -65.78) }
        return Route(id, java.time.Instant.ofEpochMilli(startMs), java.time.Instant.ofEpochMilli(startMs + seconds * 1000L), points, RouteSource.SAMSUNG_EXPORT)
    }

    @Test
    fun segmentSpeedMatchesWalkingPace() {
        val segments = Geo.segments(walk("a", 0, 1.25, 600)) { 120L }
        assertTrue(segments.isNotEmpty())
        segments.forEach { assertEquals(4.5, it.speedKmh, 0.2) }
        assertEquals(120L, segments.first().heartRate)
    }

    @Test
    fun stopsAreNotCountedAsSlowWalking() {
        assertTrue(Geo.segments(walk("b", 0, 0.05, 600)) { null }.isEmpty())
    }

    @Test
    fun zonesAggregateRepeatedWalks() {
        val tracks = listOf("a", "b").map { id ->
            val route = walk(id, 0, 1.25, 600)
            RouteTrack(route, Geo.segments(route) { 110L }, null)
        }
        val zones = Geo.zones(tracks)
        assertTrue(zones.isNotEmpty())
        assertTrue(zones.all { it.passes == 2 })
    }

    @Test
    fun bucketsSplitIntoFive() {
        val breaks = Geo.breaks((1..100).map { it.toDouble() })
        assertEquals(4, breaks.size)
        assertEquals(0, Geo.bucket(1.0, breaks))
        assertEquals(4, Geo.bucket(100.0, breaks))
    }

    @Test
    fun routineEntryRejectsUnknownRoutine() {
        assertNull(ar.nahuel.bitacora.routine.RoutineEntry.parse("2026-10-16|Z"))
        assertEquals("A", ar.nahuel.bitacora.routine.RoutineEntry.parse("2026-10-16|A")?.routineId)
    }
}

class RoutinePlanTest {
    @Test
    fun everyPlannedRoutineExists() {
        (1..8).forEach { week ->
            StrengthProgram.weekPlan(week).flatMap { it.routineIds }.forEach { id ->
                assertTrue(id, StrengthProgram.routine(id) != null)
            }
        }
    }

    @Test
    fun coreAndMobilityDoNotCountAsStrength() {
        val today = LocalDate.of(2026, 10, 20)
        val snapshot = HealthSnapshot.empty(ZoneId.of("America/Argentina/Catamarca"))
        val rec = RecommendationEngine().evaluate(snapshot, today, emptyList())
        assertEquals(Severity.ATTENTION, rec.first { it.id == "strength" }.severity)
    }
}

class SamsungMergeTest {
    private val zone = ZoneId.of("America/Argentina/Catamarca")
    private val day = LocalDate.of(2026, 10, 20)

    private fun session(id: String, minute: Long) = ExerciseSession(
        id, day.atTime(18, 0).atZone(zone).toInstant().plusSeconds(minute * 60), day.atTime(19, 0).atZone(zone).toInstant(),
        79, ExerciseKind.WALKING, null, null, null, null, null, "x",
    )

    @Test
    fun samsungStepsReplaceHealthConnect() {
        val hc = HealthSnapshot(zone, listOf(DayActivity(day, 6_019, null, null, sources = mapOf("phone" to 6_019L))), emptyList(), emptyList(), emptyList())
        val sh = ar.nahuel.bitacora.samsung.SamsungActivity(listOf(DayActivity(day, 11_231, 5_000.0, null, activeMinutes = 115, sources = mapOf("x.sdk" to 11_231L))), emptyList(), emptyList())
        val merged = ar.nahuel.bitacora.samsung.mergeSnapshot(hc, sh)
        assertEquals(11_231L, merged.days.single().steps)
        assertEquals(115L, merged.days.single().activeMinutes)
        assertEquals(6_019L, merged.days.single().healthConnectSteps)
    }

    @Test
    fun matchingSessionsKeepHealthConnectId() {
        val merged = ar.nahuel.bitacora.samsung.mergeSessions(listOf(session("sh:1:0", 1)), listOf(session("hc-1", 0)))
        assertEquals(listOf("hc-1"), merged.map { it.id })
    }

    @Test
    fun repeatedSamsungSessionsCollapseToOneRow() {
        val merged = ar.nahuel.bitacora.samsung.mergeSessions(listOf(session("sh:1:0", 1), session("sh:1:0", 1)), emptyList())
        assertEquals(listOf("sh:1:0"), merged.map { it.id })
    }
}
