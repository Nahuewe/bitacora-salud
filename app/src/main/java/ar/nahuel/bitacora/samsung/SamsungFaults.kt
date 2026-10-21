package ar.nahuel.bitacora.samsung

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SamsungFault(val error: Throwable) : Exception(summary(error), error)

object SamsungFaults {

    private const val SDK_PACKAGE = "com.samsung.android.sdk.health"
    private const val SDK_PATH = "com/samsung/android/sdk/health"

    private val faults = MutableSharedFlow<Throwable>(extraBufferCapacity = 4)

    @Volatile
    var last: Throwable? = null
        private set

    fun isSamsung(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
        cause.message.orEmpty().let { it.contains(SDK_PACKAGE) || it.contains(SDK_PATH) } ||
            cause.stackTrace.any { it.className.startsWith(SDK_PACKAGE) }
    }

    fun report(error: Throwable) {
        last = error
        faults.tryEmit(error)
    }

    suspend fun <T> guarded(timeoutMs: Long, block: suspend () -> T): T = withTimeout(timeoutMs) {
        coroutineScope {
            val watcher = launch { throw SamsungFault(faults.first()) }
            try {
                block()
            } finally {
                watcher.cancel()
            }
        }
    }
}

fun summary(error: Throwable): String {
    val root = generateSequence(error) { it.cause }.last()
    return "${root.javaClass.simpleName}: ${root.message.orEmpty().take(160)}"
}
