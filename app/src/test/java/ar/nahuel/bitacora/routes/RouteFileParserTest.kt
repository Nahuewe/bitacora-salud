package ar.nahuel.bitacora.routes

import ar.nahuel.bitacora.domain.RouteSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteFileParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun point(i: Int, lat: Double = -28.47 + i * 0.0005) =
        "{\"latitude\":$lat,\"longitude\":-65.78,\"start_time\":${1_760_000_000_000L + i * 30_000L}}"

    @Test
    fun parsesSamsungExportArray() {
        val text = (0 until 8).joinToString(",", "[", "]") { point(it) }
        val route = RouteFileParser.parse("abc.location_data.json", text, json)!!
        assertEquals("abc", route.id)
        assertEquals(RouteSource.SAMSUNG_EXPORT, route.source)
        assertEquals(1_760_000_000_000L, route.start.toEpochMilli())
    }

    @Test
    fun acceptsWrappedObjectAndSkipsInvalidPoints() {
        val points = (0 until 6).map { point(it) } + point(99, lat = 123.0) + "\"ruido\""
        val text = "{\"data\":${points.joinToString(",", "[", "]")}}"
        assertEquals(6, RouteFileParser.parse("x.location_data.json", text, json)?.points?.size)
    }

    @Test
    fun tooFewPointsIsNotARoute() {
        assertNull(RouteFileParser.parse("x.json", (0 until 3).joinToString(",", "[", "]") { point(it) }, json))
        assertNull(RouteFileParser.parse("x.json", "42", json))
    }
}
