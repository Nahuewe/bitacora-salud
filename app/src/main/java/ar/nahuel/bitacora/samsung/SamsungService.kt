package ar.nahuel.bitacora.samsung

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SamsungService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var source: SamsungHealthSource
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        source = SamsungHealthSource(applicationContext)
        messenger = Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                handle(msg.what, Bundle(msg.data), msg.replyTo)
                true
            },
        )
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handle(what: Int, args: Bundle, replyTo: Messenger?) {
        replyTo ?: return
        scope.launch {
            val data = Bundle()
            when (what) {
                SamsungIpc.STATUS -> data.putString(SamsungIpc.KEY_STATUS, SamsungIpc.encode(source.status()))
                SamsungIpc.LOAD -> {
                    val range = parseRange(args)
                    if (range == null) {
                        data.putString(SamsungIpc.KEY_ERROR, "rango inválido")
                    } else {
                        val loaded = source.load(range.first, range.second, range.third)
                        data.putString(SamsungIpc.KEY_FILE, SamsungIpc.writePayload(applicationContext, loaded))
                    }
                }
                else -> data.putString(SamsungIpc.KEY_ERROR, "pedido desconocido")
            }
            val reply = Message.obtain(null, what)
            reply.data = data
            try {
                replyTo.send(reply)
            } catch (e: RemoteException) {
                return@launch
            }
        }
    }

    private fun parseRange(args: Bundle): Triple<LocalDate, LocalDate, ZoneId>? = try {
        val from = LocalDate.parse(args.getString(SamsungIpc.KEY_FROM))
        val to = LocalDate.parse(args.getString(SamsungIpc.KEY_TO))
        val zone = ZoneId.of(args.getString(SamsungIpc.KEY_ZONE))
        if (from.isAfter(to) || from.isBefore(to.minusDays(MAX_RANGE_DAYS))) null else Triple(from, to, zone)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MAX_RANGE_DAYS = 400L
    }
}
