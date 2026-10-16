package ar.nahuel.bitacora.routes

import android.content.Context
import ar.nahuel.bitacora.domain.RoutePoint
import ar.nahuel.bitacora.domain.Route
import ar.nahuel.bitacora.domain.RouteSource
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class StoredRoute(val id: String, val start: Long, val end: Long, val time: LongArray, val lat: DoubleArray, val lon: DoubleArray)

class RouteStore(context: Context, private val json: Json) {

    private val file = File(context.filesDir, "routes.json")
    private val serializer = ListSerializer(StoredRoute.serializer())

    suspend fun load(): List<Route> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString(serializer, file.readText()).map { stored ->
                Route(
                    id = stored.id,
                    start = Instant.ofEpochMilli(stored.start),
                    end = Instant.ofEpochMilli(stored.end),
                    points = stored.lat.indices.map { RoutePoint(stored.time[it], stored.lat[it], stored.lon[it]) },
                    source = RouteSource.SAMSUNG_EXPORT,
                )
            }
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IOException) {
            emptyList()
        }
    }

    suspend fun save(routes: List<Route>) = withContext(Dispatchers.IO) {
        val stored = routes.map { route ->
            StoredRoute(
                id = route.id,
                start = route.start.toEpochMilli(),
                end = route.end.toEpochMilli(),
                time = LongArray(route.points.size) { route.points[it].timeMs },
                lat = DoubleArray(route.points.size) { route.points[it].lat },
                lon = DoubleArray(route.points.size) { route.points[it].lon },
            )
        }
        val temp = File(file.parentFile, "routes.json.tmp")
        temp.writeText(json.encodeToString(serializer, stored))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }
}
