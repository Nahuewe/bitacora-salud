package ar.nahuel.bitacora.routes

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ar.nahuel.bitacora.domain.Geo
import ar.nahuel.bitacora.domain.RoutePoint
import ar.nahuel.bitacora.domain.Route
import ar.nahuel.bitacora.domain.RouteSource
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ImportResult(val routes: List<Route>, val filesRead: Int, val filesFailed: Int)

class RouteImporter(private val context: Context, private val json: Json) {

    suspend fun import(tree: Uri): ImportResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext ImportResult(emptyList(), 0, 0)
        val files = mutableListOf<DocumentFile>()
        collect(root, files, depth = 0)
        var failed = 0
        val routes = files.mapNotNull { file ->
            val route = try {
                parse(file)
            } catch (e: IOException) {
                null
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (route == null) failed++
            route
        }
        ImportResult(routes.distinctBy { it.id }, files.size, failed)
    }

    private fun collect(dir: DocumentFile, out: MutableList<DocumentFile>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        dir.listFiles().forEach { child ->
            val name = child.name.orEmpty()
            when {
                child.isDirectory -> collect(child, out, depth + 1)
                name.endsWith(SUFFIX) && !name.contains("internal") -> out += child
            }
        }
    }

    private fun parse(file: DocumentFile): Route? {
        if (file.length() > MAX_BYTES) return null
        val text = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().decodeToString() } ?: return null
        return RouteFileParser.parse(file.name.orEmpty(), text, json)
    }

    private companion object {
        const val SUFFIX = "location_data.json"
        const val MAX_DEPTH = 6
        const val MAX_FILES = 5_000
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}

object RouteFileParser {

    private const val MIN_POINTS = 5

    fun parse(fileName: String, text: String, json: Json): Route? {
        val items = when (val element = json.parseToJsonElement(text)) {
            is JsonArray -> element
            is JsonObject -> element.values.firstOrNull { it is JsonArray } as? JsonArray ?: return null
            else -> return null
        }
        val samples = items.mapNotNull(::sample).sortedBy { it.timeMs }
        if (samples.size < MIN_POINTS) return null
        val id = fileName.substringAfterLast('/').substringBefore('.')
        return Route(
            id = id.ifBlank { samples.first().timeMs.toString() },
            start = Instant.ofEpochMilli(samples.first().timeMs),
            end = Instant.ofEpochMilli(samples.last().timeMs),
            points = Geo.thin(samples),
            source = RouteSource.SAMSUNG_EXPORT,
        )
    }

    private fun sample(item: JsonElement): RoutePoint? {
        val obj = item as? JsonObject ?: return null
        val lat = obj["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = obj["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val time = (obj["start_time"] ?: obj["time"] ?: obj["timestamp"])?.jsonPrimitive?.longOrNull ?: return null
        return if (Geo.isValid(lat, lon)) RoutePoint(time, lat, lon) else null
    }
}
