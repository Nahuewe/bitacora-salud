package ar.nahuel.bitacora

import android.app.Application
import android.content.Context
import ar.nahuel.bitacora.domain.RecommendationEngine
import ar.nahuel.bitacora.health.HealthConnectSource
import ar.nahuel.bitacora.remote.AuthRepository
import ar.nahuel.bitacora.remote.SessionStore
import ar.nahuel.bitacora.remote.SupabaseApi
import ar.nahuel.bitacora.sync.SyncService
import ar.nahuel.bitacora.sync.SyncState
import ar.nahuel.bitacora.sync.SyncWorker
import kotlinx.serialization.json.Json

class AppGraph(context: Context) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }
    val health = HealthConnectSource(context)
    val api = SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, json)
    val auth = AuthRepository(api, SessionStore(context, json))
    val syncState = SyncState(context)
    val sync = SyncService(health, auth, api, syncState, json)
    val engine = RecommendationEngine()
    val crashLog = CrashLog(context)
}

class BitacoraApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.crashLog.install()
        SyncWorker.schedule(this)
    }
}
