package ar.nahuel.bitacora.ui

import ar.nahuel.bitacora.samsung.SamsungStatus
import ar.nahuel.bitacora.ui.components.NoticeTone
import ar.nahuel.bitacora.ui.components.SamsungNotices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungNoticesTest {
    @Test
    fun readyWithoutNoteNeedsNothing() {
        assertFalse(SamsungNotices.needsAttention(SamsungStatus.Ready, null))
        assertEquals(NoticeTone.OK, SamsungNotices.of(SamsungStatus.Ready, null).tone)
        assertNull(SamsungNotices.of(SamsungStatus.Ready, null).action)
    }

    @Test
    fun everyProblemOffersAnAction() {
        listOf(
            SamsungStatus.Ready to "faltó algo",
            SamsungStatus.NeedsPermission to null,
            SamsungStatus.NeedsResolution("x") to null,
            SamsungStatus.Unavailable("x") to null,
            SamsungStatus.Paused("x") to null,
        ).forEach { (status, note) ->
            assertTrue(status.toString(), SamsungNotices.needsAttention(status, note))
            assertTrue(status.toString(), SamsungNotices.of(status, note).action != null)
        }
    }
}
