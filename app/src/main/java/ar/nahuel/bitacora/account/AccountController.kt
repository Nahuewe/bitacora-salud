package ar.nahuel.bitacora.account

import ar.nahuel.bitacora.remote.ApiError
import ar.nahuel.bitacora.remote.AuthRepository
import ar.nahuel.bitacora.sync.SyncOutcome
import ar.nahuel.bitacora.sync.SyncService
import ar.nahuel.bitacora.sync.SyncState
import ar.nahuel.bitacora.sync.SyncTrigger
import java.time.Instant

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

class AccountController(
    private val auth: AuthRepository,
    private val sync: SyncService,
    private val syncState: SyncState,
) {

    fun current(): AccountState = when {
        !auth.isConfigured -> AccountState.NotConfigured
        else -> auth.currentEmail()?.let { AccountState.SignedIn(it) } ?: AccountState.SignedOut
    }

    fun lastSuccess(): Instant? = syncState.read().lastSuccess

    suspend fun signIn(email: String, password: String): AccountState {
        if (email.isBlank() || password.isBlank()) return AccountState.Failed("Completá email y contraseña.")
        return try {
            AccountState.SignedIn(auth.signIn(email, password).email)
        } catch (e: ApiError.InvalidCredentials) {
            AccountState.Failed("Email o contraseña incorrectos.")
        } catch (e: ApiError.RateLimited) {
            AccountState.Failed("Demasiados intentos. Esperá unos minutos.")
        } catch (e: ApiError.Network) {
            AccountState.Failed("Sin conexión. Revisá internet y volvé a intentar.")
        } catch (e: ApiError) {
            AccountState.Failed("No se pudo iniciar sesión (${e.code}).")
        }
    }

    suspend fun signOut() {
        auth.signOut()
        syncState.reset()
    }

    suspend fun syncNow(): Pair<SyncUi, SyncOutcome> {
        val outcome = sync.run(SyncTrigger.MANUAL)
        val (message, isError) = SyncMessages.describe(outcome)
        return SyncUi(running = false, lastSuccess = lastSuccess(), message = message, messageIsError = isError) to outcome
    }
}

object SyncMessages {

    private const val REMOVED = "removed"
    private const val STRENGTH = "strength_logs"

    fun describe(outcome: SyncOutcome): Pair<String, Boolean> = when (outcome) {
        is SyncOutcome.Success -> {
            val sent = outcome.counts.filterKeys { it != REMOVED }.values.filter { it > 0 }.sum()
            val removed = outcome.counts[REMOVED] ?: 0
            val tail = if (removed > 0) " Marqué $removed como borrados porque ya no están en el teléfono." else ""
            if (outcome.counts[STRENGTH] == SyncService.STRENGTH_TABLE_MISSING) {
                "Sincronizado: $sent registros. Las sesiones de fuerza no se subieron: falta ejecutar la migración strength_logs en Supabase.$tail" to true
            } else {
                "Sincronizado: $sent registros enviados.$tail" to false
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
            outcome.code.startsWith("rejected_") -> "Supabase rechazó los datos (${outcome.code}). Revisá que las migraciones estén aplicadas." to true
            else -> "Falló la sincronización (${outcome.code})." to true
        }
    }
}
