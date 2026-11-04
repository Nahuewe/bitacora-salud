package ar.nahuel.bitacora.samsung

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Build
import android.os.RemoteException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SamsungClient(context: Context) {

    private val context = context.applicationContext
    private val pauses = SamsungPauseStore(this.context.filesDir, SamsungCrashReport(this.context.filesDir))

    init {
        SamsungIpc.clearStalePayloads(this.context)
    }

    fun paused(): Boolean = pauses.current() != null

    fun connectIntent(): Intent {
        pauses.resume()
        return Intent(context, SamsungConnectActivity::class.java)
    }

    suspend fun afterConnect(result: Intent?): SamsungStatus =
        pausedStatus() ?: SamsungIpc.decode(result?.getStringExtra(SamsungIpc.KEY_STATUS)) ?: status()

    suspend fun status(): SamsungStatus {
        pausedStatus()?.let { return it }
        val reply = call(SamsungIpc.STATUS, Bundle(), STATUS_TIMEOUT_MS)
        if (reply == null) pauses.pause(SILENT_DEATH)
        return pausedStatus()
            ?: SamsungIpc.decode(reply?.getString(SamsungIpc.KEY_STATUS))
            ?: SamsungStatus.Unavailable("Samsung Health no respondió.")
    }

    suspend fun load(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): SamsungActivity {
        val args = Bundle().apply {
            putString(SamsungIpc.KEY_FROM, from.toString())
            putString(SamsungIpc.KEY_TO, toInclusive.toString())
            putString(SamsungIpc.KEY_ZONE, zone.id)
        }
        val reply = call(SamsungIpc.LOAD, args, LOAD_TIMEOUT_MS)
        if (reply == null) pauses.pause(SILENT_DEATH)
        val file = reply?.getString(SamsungIpc.KEY_FILE)
        val loaded = file?.let { withContext(Dispatchers.IO) { SamsungIpc.readPayload(context, it) } }
        return loaded ?: SamsungActivity.failure(
            pauses.current()?.reason ?: reply?.getString(SamsungIpc.KEY_ERROR)?.take(120) ?: "sin respuesta",
        )
    }

    fun diagnostics(): String {
        val loader = javaClass.classLoader
        val companion = runCatching { Class.forName("$RESPONSE_CLASS\$Companion", false, loader) }.isSuccess
        val shealth = runCatching { context.packageManager.getPackageInfo(SHEALTH_PACKAGE, 0).versionName }.getOrNull() ?: "?"
        return "SDK completo: ${if (companion) "sí" else "no"} · Samsung Health $shealth · Android ${Build.VERSION.SDK_INT} · ${Build.MODEL}"
    }

    private fun pausedStatus(): SamsungStatus? = pauses.current()?.let { pause ->
        SamsungStatus.Paused("La lectura de Samsung Health falló y la pausé; la app sigue con Health Connect. Detalle: ${pause.reason}")
    }

    private suspend fun call(what: Int, args: Bundle, timeoutMs: Long): Bundle? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            lateinit var connection: ServiceConnection
            fun finish(result: Bundle?) {
                if (done.compareAndSet(false, true)) {
                    runCatching { context.unbindService(connection) }
                    if (cont.isActive) cont.resume(result)
                }
            }
            val replies = Messenger(
                Handler(Looper.getMainLooper()) { msg ->
                    finish(Bundle(msg.data))
                    true
                },
            )
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val message = Message.obtain(null, what).apply {
                        data = args
                        replyTo = replies
                    }
                    try {
                        Messenger(binder).send(message)
                    } catch (e: RemoteException) {
                        finish(null)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) = finish(null)

                override fun onBindingDied(name: ComponentName) = finish(null)

                override fun onNullBinding(name: ComponentName) = finish(null)
            }
            val bound = runCatching {
                context.bindService(Intent(context, SamsungService::class.java), connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) finish(null)
            cont.invokeOnCancellation {
                if (done.compareAndSet(false, true)) runCatching { context.unbindService(connection) }
            }
        }
    }

    private companion object {
        const val STATUS_TIMEOUT_MS = 20_000L
        const val LOAD_TIMEOUT_MS = 120_000L
        const val SILENT_DEATH = "el proceso de Samsung Health se cerró sin responder"
        const val RESPONSE_CLASS = "com.samsung.android.sdk.health.data.response.DataResponse"
        const val SHEALTH_PACKAGE = "com.sec.android.app.shealth"
    }
}
