package ar.nahuel.bitacora.ui

import android.app.Application
import android.os.RemoteException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ar.nahuel.bitacora.BitacoraApplication
import ar.nahuel.bitacora.domain.Dashboard
import ar.nahuel.bitacora.domain.DashboardBuilder
import ar.nahuel.bitacora.domain.Recommendation
import ar.nahuel.bitacora.health.HealthAccess
import ar.nahuel.bitacora.health.HealthConnectAvailability
import ar.nahuel.bitacora.remote.ApiError
import ar.nahuel.bitacora.sync.SyncOutcome
import ar.nahuel.bitacora.sync.SyncTrigger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Tab { SHEET, LOG, SETTINGS }

sealed interface AccessState {
    data object Checking : AccessState
    data class HealthConnectMissing(val needsUpdate: Boolean) : AccessState
    data object PermissionsNeeded : AccessState
    data object Ready : AccessState
}

sealed interface ContentState {
    data object Loading : ContentState
    data object Empty : ContentState
    data class Loaded(val dashboard: Dashboard, val notes: List<Recommendation>) : ContentState
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
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as BitacoraApplication).graph
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val permissionsToRequest: Set<String> get() = graph.health.requestablePermissions()

    init {
        refreshAccount()
        refresh()
    }

    fun selectTab(tab: Tab) = _state.update { it.copy(tab = tab) }

    fun refresh() {
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
                val hasAnything = snapshot.days.isNotEmpty() || snapshot.sessions.isNotEmpty() || snapshot.sleeps.isNotEmpty()
                if (!hasAnything) {
                    ContentState.Empty
                } else {
                    ContentState.Loaded(
                        dashboard = DashboardBuilder.build(snapshot, today),
                        notes = graph.engine.evaluate(snapshot, today),
                    )
                }
            } catch (e: SecurityException) {
                _state.update { it.copy(access = AccessState.PermissionsNeeded) }
                return@launch
            } catch (e: RemoteException) {
                ContentState.Error("Health Connect no respondió. Probá de nuevo en unos segundos.")
            } catch (e: IllegalStateException) {
                ContentState.Error("No se pudieron leer los datos de Health Connect.")
            }
            _state.update { it.copy(content = content) }
        }
    }

    fun onPermissionsResult(granted: Set<String>) {
        if (granted.containsAll(HealthAccess.core)) refresh()
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
            val total = outcome.counts.values.sum()
            "Sincronizado: $total registros enviados." to false
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
        const val DISPLAY_DAYS = 60L
    }
}
