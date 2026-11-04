package ar.nahuel.bitacora.ui

import android.app.Application
import android.net.Uri
import android.os.RemoteException
import androidx.health.connect.client.records.ExerciseRoute
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ar.nahuel.bitacora.BitacoraApplication
import ar.nahuel.bitacora.CrashReport
import ar.nahuel.bitacora.account.AccountState
import ar.nahuel.bitacora.account.SyncUi
import ar.nahuel.bitacora.data.ActivityData
import ar.nahuel.bitacora.domain.Dashboard
import ar.nahuel.bitacora.domain.DashboardBuilder
import ar.nahuel.bitacora.domain.HealthSnapshot
import ar.nahuel.bitacora.domain.Recommendation
import ar.nahuel.bitacora.domain.Route
import ar.nahuel.bitacora.domain.StrengthProgram
import ar.nahuel.bitacora.domain.Trends
import ar.nahuel.bitacora.domain.TrendsBuilder
import ar.nahuel.bitacora.health.HealthAccess
import ar.nahuel.bitacora.health.HealthConnectAvailability
import ar.nahuel.bitacora.routine.RoutineEntry
import ar.nahuel.bitacora.samsung.SamsungStatus
import ar.nahuel.bitacora.sync.SyncOutcome
import ar.nahuel.bitacora.ui.map.MapState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

data class Freshness(val savedAt: Instant, val updating: Boolean, val stale: Boolean)

data class UiState(
    val tab: Tab = Tab.SHEET,
    val today: LocalDate = LocalDate.now(),
    val access: AccessState = AccessState.Checking,
    val content: ContentState = ContentState.Loading,
    val freshness: Freshness? = null,
    val account: AccountState = AccountState.SignedOut,
    val sync: SyncUi = SyncUi(),
    val backgroundGranted: Boolean = false,
    val historyGranted: Boolean = false,
    val crash: CrashReport? = null,
    val samsungDiagnostics: String? = null,
    val samsung: SamsungStatus? = null,
    val samsungNote: String? = null,
    val routine: RoutineUi = RoutineUi(),
    val showMap: Boolean = false,
    val map: MapState = MapState.Idle,
)

data class RoutineUi(
    val week: Int = 1,
    val entries: List<RoutineEntry> = emptyList(),
    val program: StrengthProgram? = null,
    val programError: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as BitacoraApplication).graph
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val permissionsToRequest: Set<String> get() = graph.health.requestablePermissions()

    private var lastLoadedAt = 0L
    private var lastSnapshot: HealthSnapshot? = null
    private var samsungRoutes: List<Route> = emptyList()

    init {
        _state.update {
            it.copy(
                account = graph.account.current(),
                crash = graph.crashLog.last(),
                samsungDiagnostics = runCatching { graph.samsung.diagnostics() }.getOrNull(),
            )
        }
        viewModelScope.launch { loadProgram() }
        refresh(force = true)
    }

    private suspend fun loadProgram() {
        val result = withContext(Dispatchers.IO) { runCatching { graph.program() } }
        _state.update { state ->
            state.copy(
                routine = state.routine.copy(
                    program = result.getOrNull(),
                    programError = result.exceptionOrNull()?.let { "No se pudo cargar la rutina: ${it.message ?: it.javaClass.simpleName}" },
                ),
            )
        }
        refreshRoutine()
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

    private fun loggedStrengthDays(): List<LocalDate> {
        val strengthIds = _state.value.routine.program?.strengthIds ?: return emptyList()
        return graph.routineLog.entries().filter { it.routineId in strengthIds }.map { it.date }
    }

    private fun refreshRoutine() {
        val today = LocalDate.now()
        val start = graph.routineLog.startDate(today)
        _state.update {
            it.copy(routine = it.routine.copy(week = StrengthProgram.weekNumber(start, today), entries = graph.routineLog.entries()))
        }
    }

    fun onSamsungStatus(status: SamsungStatus) {
        _state.update { it.copy(samsung = status, samsungNote = null, map = MapState.Idle) }
        if (status == SamsungStatus.Ready) refresh(force = true, full = true)
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
                MapState.Ready(graph.maps.load(lastSnapshot?.sessions.orEmpty(), samsungRoutes), message)
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
                graph.maps.import(tree)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "No se pudo leer la carpeta (${e.javaClass.simpleName})."
            }
            loadMap(message)
        }
    }

    fun onRouteConsent(recordId: String, route: ExerciseRoute?) {
        viewModelScope.launch {
            val session = lastSnapshot?.sessions?.firstOrNull { it.id == recordId }
            loadMap(graph.maps.addConsented(session, route))
        }
    }

    fun clearImportedRoutes() {
        viewModelScope.launch {
            graph.maps.clearImported()
            loadMap("Borré los recorridos importados de este teléfono.")
        }
    }

    fun refresh(force: Boolean = false, full: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && _state.value.content is ContentState.Loaded && now - lastLoadedAt < RELOAD_INTERVAL_MS) return
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            _state.update { it.copy(today = today, sync = it.sync.copy(lastSuccess = graph.account.lastSuccess())) }

            if (!checkAccess()) return@launch

            if (_state.value.content !is ContentState.Loaded) {
                graph.activity.cached(zone)?.let { show(it, today, updating = true) }
            }
            _state.update { it.copy(freshness = it.freshness?.copy(updating = true)) }

            try {
                val data = graph.activity.refresh(today, zone, full)
                _state.update { it.copy(samsung = data.samsungStatus, samsungNote = data.note) }
                show(data, today, updating = false)
                lastLoadedAt = System.currentTimeMillis()
            } catch (e: SecurityException) {
                _state.update { it.copy(access = AccessState.PermissionsNeeded) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is RemoteException) {
                    "Health Connect no respondió. Probá de nuevo en unos segundos."
                } else {
                    "No se pudieron leer los datos de Health Connect (${e.javaClass.simpleName})."
                }
                _state.update { state ->
                    if (state.content is ContentState.Loaded) {
                        state.copy(freshness = state.freshness?.copy(updating = false, stale = true))
                    } else {
                        state.copy(content = ContentState.Error(message), freshness = null)
                    }
                }
            }
        }
    }

    private suspend fun checkAccess(): Boolean {
        when (graph.health.availability()) {
            HealthConnectAvailability.UNAVAILABLE -> {
                _state.update { it.copy(access = AccessState.HealthConnectMissing(needsUpdate = false)) }
                return false
            }
            HealthConnectAvailability.NEEDS_UPDATE -> {
                _state.update { it.copy(access = AccessState.HealthConnectMissing(needsUpdate = true)) }
                return false
            }
            HealthConnectAvailability.AVAILABLE -> Unit
        }
        val granted = runCatching { graph.health.grantedPermissions() }.getOrDefault(emptySet())
        val ready = granted.containsAll(HealthAccess.core)
        _state.update {
            it.copy(
                backgroundGranted = HealthAccess.BACKGROUND in granted,
                historyGranted = HealthAccess.HISTORY in granted,
                access = if (ready) AccessState.Ready else AccessState.PermissionsNeeded,
            )
        }
        return ready
    }

    private suspend fun show(data: ActivityData, today: LocalDate, updating: Boolean) {
        lastSnapshot = data.snapshot
        samsungRoutes = data.samsungRoutes
        val snapshot = data.snapshot
        val hasAnything = snapshot.days.isNotEmpty() || snapshot.sessions.isNotEmpty() || snapshot.sleeps.isNotEmpty()
        val content = if (!hasAnything) {
            ContentState.Empty
        } else {
            val strengthDays = loggedStrengthDays()
            withContext(Dispatchers.Default) {
                ContentState.Loaded(
                    dashboard = DashboardBuilder.build(snapshot, today),
                    notes = graph.engine.evaluate(snapshot, today, strengthDays),
                    trends = TrendsBuilder.build(snapshot, today),
                )
            }
        }
        _state.update {
            it.copy(
                content = content,
                freshness = Freshness(data.savedAt, updating = updating, stale = false),
                map = if (data.fromCache) it.map else MapState.Idle,
            )
        }
    }

    fun onPermissionsResult(granted: Set<String>) {
        if (granted.containsAll(HealthAccess.core)) refresh(force = true)
        else _state.update { it.copy(access = AccessState.PermissionsNeeded) }
    }

    fun signIn(email: String, password: String) {
        _state.update { it.copy(account = AccountState.SigningIn) }
        viewModelScope.launch {
            val next = graph.account.signIn(email, password)
            _state.update { it.copy(account = next) }
            if (next is AccountState.SignedIn) syncNow()
        }
    }

    fun signOut() {
        viewModelScope.launch {
            graph.account.signOut()
            _state.update { it.copy(account = AccountState.SignedOut, sync = SyncUi()) }
        }
    }

    fun syncNow() {
        if (_state.value.sync.running) return
        _state.update { it.copy(sync = it.sync.copy(running = true, message = null)) }
        viewModelScope.launch {
            val (sync, outcome) = graph.account.syncNow()
            _state.update { it.copy(sync = sync) }
            if (outcome is SyncOutcome.NotSignedIn) _state.update { it.copy(account = graph.account.current()) }
            refreshRoutine()
        }
    }

    private companion object {
        const val RELOAD_INTERVAL_MS = 5 * 60 * 1000L
    }
}
