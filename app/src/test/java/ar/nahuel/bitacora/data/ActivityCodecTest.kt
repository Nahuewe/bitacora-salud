package ar.nahuel.bitacora.data

import ar.nahuel.bitacora.domain.DayActivity
import ar.nahuel.bitacora.domain.ExerciseKind
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.HealthSnapshot
import ar.nahuel.bitacora.domain.HeartPoint
import ar.nahuel.bitacora.domain.Route
import ar.nahuel.bitacora.domain.RoutePoint
import ar.nahuel.bitacora.domain.RouteSource
import ar.nahuel.bitacora.domain.SleepSession
import ar.nahuel.bitacora.domain.WeightEntry
import ar.nahuel.bitacora.samsung.SamsungActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityCodecTest {
    private val zone = ZoneId.of("America/Argentina/Catamarca")
    private val t0 = Instant.parse("2026-10-20T21:00:00Z")
    private val route = Route(
        "sh:abc:0", t0, t0.plusSeconds(1800),
        (0 until 6).map { RoutePoint(t0.toEpochMilli() + it * 60_000L, -28.47 + it * 0.001, -65.78) },
        RouteSource.SAMSUNG_SDK,
        listOf(HeartPoint(t0.toEpochMilli(), 102), HeartPoint(t0.toEpochMilli() + 60_000, 110)),
    )
    private val session = ExerciseSession("hc-1", t0, t0.plusSeconds(1800), 79, ExerciseKind.WALKING, "Caminata", 2_400.0, 108, 131, 180.5, "com.sec.android.app.shealth")
    private val day = DayActivity(LocalDate.of(2026, 10, 20), 11_231, 7_000.0, 320.0, 115, mapOf("x" to 11_231L), 6_019)

    @Test
    fun samsungPayloadSurvivesTheProcessBoundary() {
        val original = SamsungActivity(listOf(day), listOf(session), listOf(route), listOf("distancia"), "TimeoutException")
        assertEquals(original, ActivityCodec.decodeSamsung(ActivityCodec.encodeSamsung(original)))
    }

    @Test
    fun cacheRoundTrip() {
        val snapshot = HealthSnapshot(
            zone,
            listOf(day),
            listOf(session),
            listOf(SleepSession("s1", t0, t0.plusSeconds(25_200), 60, 80, 220, 20, 58)),
            listOf(WeightEntry("w1", t0, 82.4)),
        )
        val cache = CachedActivity(snapshot, day.date.minusDays(182), day.date, t0, t0, listOf(route), true)
        assertEquals(cache, ActivityCodec.decodeCache(ActivityCodec.encodeCache(cache)))
    }

    @Test
    fun corruptOrForeignPayloadIsRejected() {
        assertNull(ActivityCodec.decodeSamsung("{\"version\":9,\"days\":[],\"sessions\":[],\"routes\":[],\"failed\":[]}"))
        assertNull(ActivityCodec.decodeSamsung("no es json"))
        assertNull(ActivityCodec.decodeCache("{}"))
    }

    @Test
    fun routeWithMismatchedArraysIsDropped() {
        val text = ActivityCodec.encodeSamsung(SamsungActivity(emptyList(), emptyList(), listOf(route)))
            .replace("\"lon\":[", "\"lon\":[1.0,")
        assertEquals(0, ActivityCodec.decodeSamsung(text)?.routes?.size)
    }
}
