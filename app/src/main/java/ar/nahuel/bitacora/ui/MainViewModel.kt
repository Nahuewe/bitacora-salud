package ar.nahuel.bitacora.ui

import android.app.Application
import android.os.RemoteException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ar.nahuel.bitacora.BitacoraApplication
import ar.nahuel.bitacora.domain.Dashboard
import ar.nahuel.bitacora.domain.DashboardBuilder
import ar.nahuel.bitacora.domain.Recommendation
import ar.nahuel.bitacora.domain.Trends
import ar.nahuel.bitacora.domain.TrendsBuilder
import ar.nahuel.bitacora.CrashReport
import ar.nahuel.bitacora.domain.HealthSnapshot
import ar.nahuel.bitacora.domain.StrengthProgram
import ar.nahuel.bitacora.routine.RoutineEntry
import ar.nahuel.bitacora.domain.ExerciseKind
import ar.nahuel.bitacora.domain.Geo
import ar.nahuel.bitacora.domain.Route
import ar.nahuel.bitacora.domain.RouteSource
import ar.nahuel.bitacora.domain.RouteTrack
import ar.nahuel.bitacora.domain.Zone
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import ar.nahuel.bitacora.health.HealthAccess
import ar.nahuel.bitacora.health.HealthConnectAvailability
import ar.nahuel.bitacora.remote.ApiError
import ar.nahuel.bitacora.sync.SyncOutcome
import ar.nahuel.bitacora.sync.SyncService
import ar.nahuel.bitacora.sync.SyncTrigger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Tab { SHEET, TRENDS, ROUTINE, LOG, SETTINGS }

sealed interface AccessState {
    data object Checking : AccessState
    data class HealthConnectMissing(val needsUpdate: Boolean) : AccessState
    data object PermissionsNeeded : AccessState
    data object Ready : AccessState
}

sealed interface ContentState {
    data object Loading : ContentState
    data object Empty : ContentState
    data class Loaded(val dashboard: Dashboard, val notes: List<Recommendation>, val trends: Trends) : ContentState
    data class Error(val message: String) : ContentState
}

sealed interface AccountState {
    data object NotConfigured : AccountState
    data object SignedOut : AccountState
    data object SigningIn : AccountState
    data class SignedIn(val email: String) : AccountState
    data class Failed(val message: String) : AccountState
}

data class SyncUi(
    val running: Boolean = false,
    val lastSuccess: Instant? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

data class UiState(
    val tab: Tab = Tab.SHEET,
    val today: LocalDate = LocalDate.now(),
    val access: AccessState = AccessState.Checking,
    val content: ContentState = ContentState.Loading,
    val account: AccountState = AccountState.SignedOut,
    val sync: SyncUi = SyncUi(),
    val backgroundGranted: Boolean = false,
    val historyGranted: Boolean = false,
    val crash: CrashReport? = null,
    val routine: RoutineUi = RoutineUi(),
    val showMap: Boolean = false,
    val map: MapState = MapState.Idle,
)

data class MapData(
    val tracks: List<RouteTrack>,
    val zones: List<Zone>,
    val speedBreaks: List<Double>,
    val heartBreaks: List<Double>,
    val fromHealthConnect: Int,
    val imported: Int,
    val consentRequired: Int,
)

sealed interface MapState {
    data object Idle : MapState
    data class Loading(val message: String) : MapState
    data class Ready(val data: MapData, val message: String? = null) : MapState
    data class Error(val message: String) : MapState
}

data class RoutineUi(
    val week: Int = 1,
    val entries: List<RoutineEntry> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as BitacoraApplication).graph
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val permissionsToRequest: Set<String> get() = graph.health.requestablePermissions()

    private var lastLoadedAt = 0L
    private var lastSnapshot: HealthSnapshot? = null

    init {
        refreshAccount()
        _state.update { it.copy(crash = graph.crashLog.last()) }
        refreshRoutine()
        refresh(force = true)
    }

    fun toggleRoutine(routineId: String) {
        graph.routineLog.toggle(LocalDate.now(), routineId)
        refreshRoutine()
        val snapshot = lastSnapshot ?: return
        val today = LocalDate.now()
        _state.update { state ->
            val content = state.content
            if (content is ContentState.Loaded) {
                state.copy(content = content.copy(notes = graph.engine.evaluate(snapshot, today, loggedStrengthDays())))
            } else {
                state
            }
        }
    }

    private fun loggedStrengthDays(): List<LocalDate> =
        graph.routineLog.entries().filter { it.routineId in StrengthProgram.strengthIds }.map { it.date }

    private fun refreshRoutine() {
        val today = LocalDate.now()
        val start = graph.routineLog.startDate(today)
        _state.update {
            it.copy(routine = RoutineUi(StrengthProgram.weekNumber(start, today), graph.routineLog.entries()))
        }
    }

    fun clearCrash() {
        graph.crashLog.clear()
        _state.update { it.copy(crash = null) }
    }

    fun selectTab(tab: Tab) = _state.update { it.copy(tab = tab) }

    fun openMap() {
        _state.update { it.copy(showMap = true) }
        if (_state.value.map !is MapState.Ready) loadMap()
    }

    fun closeMap() = _state.update { it.copy(showMap = false) }

    fun loadMap(message: String? = null) {
        _state.update { it.copy(map = MapState.Loading("Buscando recorridos…")) }
        viewModelScope.launch {
            val next = try {
                val sessions = lastSnapshot?.sessions.orEmpty()
                val walks = sessions.filter {
                    (it.kind == ExerciseKind.WALKING || it.kind == ExerciseKind.RUNNING) &&
                        it.start.isAfter(java.time.Instant.now().minus(java.time.Duration.ofDays(ROUTE_DAYS)))
                }
                val fromHc = runCatching { graph.health.routes(walks) }.getOrNull()
                val imported = graph.routeStore.load()
                val hcRoutes = fromHc?.routes.orEmpty()
                val importedOnly = imported.filter { route ->
                    val session = Geo.matchSession(route, sessions)
                    session == null || hcRoutes.none { it.id == session.id }
                }
                val routes = (hcRoutes + importedOnly).sortedBy { it.start }
                if (routes.isEmpty()) {
                    MapState.Ready(MapData(emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, fromHc?.consentRequired ?: 0), message)
                } else {
                    val heart = graph.health.heartRateSamples(routes.first().start, routes.last().end)
                    val data = withContext(Dispatchers.Default) {
                        val tracks = routes.map { route ->
                            RouteTrack(route, Geo.segments(route) { heart.at(it) }, Geo.matchSession(route, sessions))
                        }
                        val segments = tracks.flatMap { it.segments }
                        MapData(
                            tracks = tracks,
                            zones = Geo.zones(tracks),
                            speedBreaks = Geo.breaks(segments.map { it.speedKmh }),
                            heartBreaks = Geo.breaks(segments.mapNotNull { it.heartRate?.toDouble() }),
                            fromHealthConnect = hcRoutes.size,
                            imported = importedOnly.size,
                            consentRequired = fromHc?.consentRequired ?: 0,
                        )
                    }
                    MapState.Ready(data, message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MapState.Error("No se pudieron cargar los recorridos (${e.javaClass.simpleName}).")
            }
            _state.update { it.copy(map = next) }
        }
    }

    fun importRoutes(tree: Uri) {
        _state.update { it.copy(map = MapState.Loading("Leyendo la exportación de Samsung Health…")) }
        viewModelScope.launch {
            val message = try {
                val result = graph.routeImporter.import(tree)
                val existing = graph.routeStore.load()
                val merged = (existing + result.routes).distinctBy { it.id }
                graph.routeStore.save(merged)
                when {
                    result.filesRead == 0 -> "No encontré archivos de recorridos en esa carpeta. Elegí la carpeta samsunghealth_… completa."
                    result.routes.isEmpty() -> "Leí ${result.filesRead} archivos pero ninguno tenía puntos de GPS válidos."
                    else -> "Importé ${result.routes.size} recorridos de ${result.filesRead} archivos."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "No se pudo leer la carpeta (${e.javaClass.simpleName})."
            }
            loadMap(message)
        }
    }

    fun clearImportedRoutes() {
        viewModelScope.launch {
            graph.routeStore.clear()
            loadMap("Borré los recorridos importados de este teléfono.")
        }
    }

    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && _state.value.content is ContentState.Loaded && now - lastLoadedAt < RELOAD_INTERVAL_MS) return
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            _state.update { it.copy(today = today, sync = it.sync.copy(lastSuccess = graph.syncState.read().lastSuccess)) }

            when (graph.health.availability()) {
                HealthConnectAvailability.UNAVAILABLE -> {
                    _state.update { it.copy(access = AccessState.HealthConnectMissing(needsUpdate = false)) }
                    return@launch
                }
                HealthConnectAvailability.NEEDS_UPDATE -> {
                    _state.update { it.copy(access = AccessState.HealthConnectMissing(needsUpdate = true)) }
                    return@launch
                }
                HealthConnectAvailability.AVAILABLE -> Unit
            }

            val granted = runCatching { graph.health.grantedPermissions() }.getOrDefault(emptySet())
            _state.update {
                it.copy(
                    backgroundGranted = HealthAccess.BACKGROUND in granted,
                    historyGranted = HealthAccess.HISTORY in granted,
                )
            }
            if (!granted.containsAll(HealthAccess.core)) {
                _state.update { it.copy(access = AccessState.PermissionsNeeded) }
                return@launch
            }

            _state.update {
                it.copy(
                    access = AccessState.Ready,
                    content = if (it.content is ContentState.Loaded) it.content else ContentState.Loading,
                )
            }
            val content = try {
                val snapshot = graph.health.snapshot(today.minusDays(DISPLAY_DAYS), today, zone)
                lastSnapshot = snapshot
                val hasAnything = snapshot.days.isNotEmpty() || snapshot.sessions.isNotEmpty() || snapshot.sleeps.isNotEmpty()
                if (!hasAnything) {
                    ContentState.Empty
                } else {
                    lastLoadedAt = System.currentTimeMillis()
                    ContentState.Loaded(
                        dashboard = DashboardBuilder.build(snapshot, today),
                        notes = graph.engine.evaluate(snapshot, today, loggedStrengthDays()),
                        trends = TrendsBuilder.build(snapshot, today),
                    )
                }
            } catch (e: SecurityException) {
                _state.update { it.copy(access = AccessState.PermissionsNeeded) }
                return@launch
            } catch (e: RemoteException) {
                ContentState.Error("Health Connect no respondió. Probá de nuevo en unos segundos.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ContentState.Error("No se pudieron leer los datos de Health Connect (${e.javaClass.simpleName}).")
            }
            _state.update { it.copy(content = content) }
        }
    }

    fun onPermissionsResult(granted: Set<String>) {
        if (granted.containsAll(HealthAccess.core)) refresh(force = true)
        else _state.update { it.copy(access = AccessState.PermissionsNeeded) }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _state.update { it.copy(account = AccountState.Failed("Completá email y contraseña.")) }
            return
        }
        _state.update { it.copy(account = AccountState.SigningIn) }
        viewModelScope.launch {
            val next = try {
                AccountState.SignedIn(graph.auth.signIn(email, password).email)
            } catch (e: ApiError.InvalidCredentials) {
                AccountState.Failed("Email o contraseña incorrectos.")
            } catch (e: ApiError.RateLimited) {
                AccountState.Failed("Demasiados intentos. Esperá unos minutos.")
            } catch (e: ApiError.Network) {
                AccountState.Failed("Sin conexión. Revisá internet y volvé a intentar.")
            } catch (e: ApiError) {
                AccountState.Failed("No se pudo iniciar sesión (${e.code}).")
            }
            _state.update { it.copy(account = next) }
            if (next is AccountState.SignedIn) syncNow()
        }
    }

    fun signOut() {
        viewModelScope.launch {
            graph.auth.signOut()
            graph.syncState.reset()
            _state.update { it.copy(account = AccountState.SignedOut, sync = SyncUi()) }
        }
    }

    fun syncNow() {
        if (_state.value.sync.running) return
        _state.update { it.copy(sync = it.sync.copy(running = true, message = null)) }
        viewModelScope.launch {
            val outcome = graph.sync.run(SyncTrigger.MANUAL)
            val (message, isError) = describe(outcome)
            _state.update {
                it.copy(
                    sync = SyncUi(
                        running = false,
                        lastSuccess = graph.syncState.read().lastSuccess,
                        message = message,
                        messageIsError = isError,
                    ),
                )
            }
            if (outcome is SyncOutcome.NotSignedIn) refreshAccount()
            refreshRoutine()
        }
    }

    private fun refreshAccount() {
        val account = when {
            !graph.auth.isConfigured -> AccountState.NotConfigured
            else -> graph.auth.currentEmail()?.let { AccountState.SignedIn(it) } ?: AccountState.SignedOut
        }
        _state.update { it.copy(account = account) }
    }

    private fun describe(outcome: SyncOutcome): Pair<String, Boolean> = when (outcome) {
        is SyncOutcome.Success -> {
            val total = outcome.counts.values.filter { it > 0 }.sum()
            if (outcome.counts["strength_logs"] == SyncService.STRENGTH_TABLE_MISSING) {
                "Sincronizado: $total registros. Las sesiones de fuerza no se subieron: falta ejecutar la migración strength_logs en Supabase." to true
            } else {
                "Sincronizado: $total registros enviados." to false
            }
        }
        SyncOutcome.AlreadyRunning -> "Ya hay una sincronización en curso." to false
        SyncOutcome.NotConfigured -> "La app se compiló sin datos de Supabase." to true
        SyncOutcome.NotSignedIn -> "Iniciá sesión para sincronizar." to true
        SyncOutcome.HealthConnectUnavailable -> "Health Connect no está disponible." to true
        SyncOutcome.MissingPermissions -> "Faltan permisos de Health Connect." to true
        is SyncOutcome.Failed -> when {
            outcome.code == "network" -> "Sin conexión. Se reintenta solo en la próxima ventana." to true
            outcome.code == "unauthorized" -> "La sesión venció. Volvé a iniciar sesión." to true
            outcome.code.startsWith("rejected_") -> "Supabase rechazó los datos (${outcome.code}). Revisá que la migración esté aplicada." to true
            else -> "Falló la sincronización (${outcome.code})." to true
        }
    }

    private companion object {
        const val DISPLAY_DAYS = 182L
        const val ROUTE_DAYS = 120L
        const val RELOAD_INTERVAL_MS = 5 * 60 * 1000L
    }
}
