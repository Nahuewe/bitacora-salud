package ar.nahuel.bitacora

import android.app.Application
import android.content.Context
import ar.nahuel.bitacora.account.AccountController
import ar.nahuel.bitacora.data.ActivityRepository
import ar.nahuel.bitacora.data.ActivityStore
import ar.nahuel.bitacora.domain.RecommendationEngine
import ar.nahuel.bitacora.domain.StrengthProgram
import ar.nahuel.bitacora.domain.StrengthProgramParser
import ar.nahuel.bitacora.routes.MapLoader
import ar.nahuel.bitacora.health.HealthConnectSource
import ar.nahuel.bitacora.remote.AuthRepository
import ar.nahuel.bitacora.remote.SessionStore
import ar.nahuel.bitacora.remote.SupabaseApi
import ar.nahuel.bitacora.routine.RoutineLog
import ar.nahuel.bitacora.routes.RouteImporter
import ar.nahuel.bitacora.routes.RouteStore
import ar.nahuel.bitacora.samsung.SamsungClient
import ar.nahuel.bitacora.samsung.SamsungIpc
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
    val samsung = SamsungClient(context)
    val activity = ActivityRepository(health, samsung, ActivityStore(context))
    val sync = SyncService(health, auth, api, syncState, json, routineLog, activity)
    val account = AccountController(auth, sync, syncState)
    val maps = MapLoader(health, routeStore, routeImporter)

    private val assets = context.applicationContext.assets
    private val programLock = Any()
    private var program: StrengthProgram? = null

    fun program(): StrengthProgram = synchronized(programLock) {
        program ?: assets.open(PROGRAM_ASSET).use { StrengthProgramParser.parse(it.readBytes().decodeToString()) }.also { program = it }
    }

    private companion object {
        const val PROGRAM_ASSET = "strength_program.json"
    }
}

class BitacoraApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName().endsWith(SamsungIpc.PROCESS_SUFFIX)) {
            SamsungIpc.installProcessGuard(this)
            return
        }
        graph = AppGraph(this)
        graph.crashLog.install()
        SyncWorker.schedule(this)
    }
}
