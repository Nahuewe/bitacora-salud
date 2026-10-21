package ar.nahuel.bitacora

import android.app.Application
import android.content.Context
import android.os.Looper
import ar.nahuel.bitacora.domain.RecommendationEngine
import ar.nahuel.bitacora.health.HealthConnectSource
import ar.nahuel.bitacora.remote.AuthRepository
import ar.nahuel.bitacora.remote.SessionStore
import ar.nahuel.bitacora.remote.SupabaseApi
import ar.nahuel.bitacora.routine.RoutineLog
import ar.nahuel.bitacora.routes.RouteImporter
import ar.nahuel.bitacora.routes.RouteStore
import ar.nahuel.bitacora.samsung.SamsungFaults
import ar.nahuel.bitacora.samsung.SamsungHealthSource
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
    val engine = RecommendationEngine()
    val crashLog = CrashLog(context)
    val routineLog = RoutineLog(context)
    val routeStore = RouteStore(context, json)
    val routeImporter = RouteImporter(context, json)
    val samsung = SamsungHealthSource(context)
    val sync = SyncService(health, auth, api, syncState, json, routineLog, samsung)
}

class BitacoraApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.crashLog.last()?.let { graph.samsung.pauseAfterCrash(it.at.toEpochMilli()) }
        graph.crashLog.install { thread, error ->
            val offMain = thread !== Looper.getMainLooper().thread
            if (offMain && SamsungFaults.isSamsung(error)) {
                SamsungFaults.report(error)
                true
            } else {
                false
            }
        }
        SyncWorker.schedule(this)
    }
}
