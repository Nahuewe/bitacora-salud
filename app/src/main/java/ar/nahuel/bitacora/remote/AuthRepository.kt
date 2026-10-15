package ar.nahuel.bitacora.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AuthRepository(
    private val api: SupabaseApi,
    private val store: SessionStore,
) {
    private val refreshLock = Mutex()

    val isConfigured: Boolean get() = api.isConfigured

    fun currentEmail(): String? = store.load()?.email

    suspend fun signIn(email: String, password: String): AuthSession {
        val session = api.signIn(email, password)
        store.save(session)
        return session
    }

    suspend fun signOut() {
        store.load()?.let { api.signOut(it) }
        store.clear()
    }

    suspend fun validSession(): AuthSession? = refreshLock.withLock {
        val session = store.load() ?: return@withLock null
        val now = System.currentTimeMillis() / 1000
        if (session.expiresAt - now > REFRESH_MARGIN_SECONDS) return@withLock session
        try {
            api.refresh(session).also { store.save(it) }
        } catch (e: ApiError.InvalidCredentials) {
            store.clear()
            null
        } catch (e: ApiError.Unauthorized) {
            store.clear()
            null
        }
    }

    private companion object {
        const val REFRESH_MARGIN_SECONDS = 120L
    }
}
