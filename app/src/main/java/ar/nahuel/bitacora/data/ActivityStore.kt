package ar.nahuel.bitacora.data

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ActivityStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    suspend fun read(): CachedActivity? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() > MAX_BYTES) return@withContext null
        try {
            ActivityCodec.decodeCache(file.readText())
        } catch (e: IOException) {
            null
        }
    }

    suspend fun write(cache: CachedActivity) = withContext(Dispatchers.IO) {
        val text = ActivityCodec.encodeCache(cache)
        if (text.length > MAX_BYTES) return@withContext
        AtomicFile.write(file, text)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
    }

    private companion object {
        const val FILE_NAME = "activity_cache.json"
        const val MAX_BYTES = 24L * 1024 * 1024
    }
}

object AtomicFile {
    fun write(target: File, text: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw IOException("no se pudo reemplazar ${target.name}")
        }
    }
}
