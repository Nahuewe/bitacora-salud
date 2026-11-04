package ar.nahuel.bitacora.samsung

import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungFaultStoreTest {
    private val dir: File = Files.createTempDirectory("faults").toFile()
    private var now = Instant.parse("2026-11-04T12:00:00Z")
    private val crashes = SamsungCrashReport(dir)
    private val pauses = SamsungPauseStore(dir, crashes) { now }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun crashFromOtherProcessPausesUntilResumed() {
        assertNull(pauses.current())
        crashes.write(Thread.currentThread(), NoClassDefFoundError("com/samsung/x\u0000"), now)
        val pause = pauses.current()
        assertNotNull(pause)
        assertTrue(pause!!.reason.none { it.isISOControl() })
        pauses.resume()
        assertNull(pauses.current())
        now = now.plusSeconds(60)
        crashes.write(Thread.currentThread(), IllegalStateException("otra"), now)
        assertNotNull(pauses.current())
    }

    @Test
    fun silentDeathPauses() {
        pauses.pause("el proceso se cerró")
        assertEquals("el proceso se cerró", pauses.current()?.reason)
    }

    @Test
    fun corruptFilesAreIgnored() {
        File(dir, "samsung_crash.json").writeText("{\"v\":1,\"at\":\"x\"}")
        File(dir, "samsung_pause.json").writeText("basura")
        assertNull(pauses.current())
    }

    @Test
    fun crashFromTheFutureIsRejected() {
        crashes.write(Thread.currentThread(), IllegalStateException("x"), now.plusSeconds(3 * 86_400))
        assertNull(crashes.read(now))
    }

    @Test
    fun statusSurvivesIpcEncoding() {
        listOf(
            SamsungStatus.Ready,
            SamsungStatus.NeedsPermission,
            SamsungStatus.Unavailable("sin red | reintentar"),
            SamsungStatus.Paused("falló"),
        ).forEach { assertEquals(it, SamsungIpc.decode(SamsungIpc.encode(it))) }
        assertNull(SamsungIpc.decode("OTRA|cosa"))
    }
}
