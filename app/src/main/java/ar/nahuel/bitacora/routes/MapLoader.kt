package ar.nahuel.bitacora.routes

import androidx.health.connect.client.records.ExerciseRoute
import ar.nahuel.bitacora.domain.ExerciseKind
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.Geo
import ar.nahuel.bitacora.domain.Route
import ar.nahuel.bitacora.domain.RoutePoint
import ar.nahuel.bitacora.domain.RouteSource
import ar.nahuel.bitacora.domain.RouteTrack
import ar.nahuel.bitacora.health.HealthAccess
import ar.nahuel.bitacora.health.HealthConnectSource
import android.net.Uri
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MapData(
    val tracks: List<RouteTrack>,
    val speedBreaks: List<Double>,
    val heartBreaks: List<Double>,
    val fromHealthConnect: Int,
    val fromSamsung: Int,
    val imported: Int,
    val consentRequired: Int,
    val consentIds: List<String>,
    val checked: Int,
    val withoutRoute: Int,
    val routePermission: Boolean,
)

class MapLoader(
    private val health: HealthConnectSource,
    private val store: RouteStore,
    private val importer: RouteImporter,
) {

    suspend fun load(sessions: List<ExerciseSession>, samsungRoutes: List<Route>, now: Instant = Instant.now()): MapData {
        val walks = sessions.filter {
            (it.kind == ExerciseKind.WALKING || it.kind == ExerciseKind.RUNNING) && it.start.isAfter(now.minus(ROUTE_WINDOW))
        }
        val walksWithoutSamsung = walks.filter { walk -> samsungRoutes.none { Geo.matchSession(it, listOf(walk)) != null } }
        val fromHc = runCatching { health.routes(walksWithoutSamsung) }.getOrNull()
        val routePermission = runCatching { HealthAccess.ROUTES in health.grantedPermissions() }.getOrDefault(false)
        val primary = samsungRoutes + fromHc?.routes.orEmpty()
        val importedOnly = store.load().filter { route ->
            val session = Geo.matchSession(route, sessions)
            session == null || primary.none { Geo.matchSession(it, listOf(session)) != null }
        }
        val routes = (primary + importedOnly).sortedBy { it.start }
        val heart = if (routes.isEmpty()) null else health.heartRateSamples(routes.first().start, routes.last().end)
        return withContext(Dispatchers.Default) {
            val tracks = routes.map { route ->
                RouteTrack(route, Geo.segments(route) { route.heartAt(it) ?: heart?.at(it) }, Geo.matchSession(route, sessions))
            }
            val segments = tracks.flatMap { it.segments }
            MapData(
                tracks = tracks,
                speedBreaks = Geo.breaks(segments.map { it.speedKmh }),
                heartBreaks = Geo.breaks(segments.mapNotNull { it.heartRate?.toDouble() }),
                fromHealthConnect = fromHc?.routes.orEmpty().size,
                fromSamsung = samsungRoutes.size,
                imported = importedOnly.size,
                consentRequired = fromHc?.consentRequired ?: 0,
                consentIds = fromHc?.consentIds.orEmpty(),
                checked = fromHc?.checked ?: walks.size,
                withoutRoute = fromHc?.withoutRoute ?: 0,
                routePermission = routePermission,
            )
        }
    }

    suspend fun import(tree: Uri): String {
        val result = importer.import(tree)
        store.save((store.load() + result.routes).distinctBy { it.id })
        return when {
            result.filesRead == 0 -> "No encontré archivos de recorridos en esa carpeta. Elegí la carpeta samsunghealth_… completa."
            result.routes.isEmpty() -> "Leí ${result.filesRead} archivos pero ninguno tenía puntos de GPS válidos."
            else -> "Importé ${result.routes.size} recorridos de ${result.filesRead} archivos."
        }
    }

    suspend fun addConsented(session: ExerciseSession?, route: ExerciseRoute?): String {
        route ?: return "No se autorizó el recorrido."
        val points = route.route
            .filter { Geo.isValid(it.latitude, it.longitude) }
            .map { RoutePoint(it.time.toEpochMilli(), it.latitude, it.longitude) }
        if (session == null || points.size < MIN_POINTS) return "Health Connect devolvió el recorrido vacío."
        val added = Route(session.id, session.start, session.end, Geo.thin(points), RouteSource.HEALTH_CONNECT)
        store.save((store.load() + added).distinctBy { it.id })
        return "Recorrido agregado."
    }

    suspend fun clearImported() = store.clear()

    companion object {
        val ROUTE_WINDOW: Duration = Duration.ofDays(120)
        private const val MIN_POINTS = 5
    }
}
