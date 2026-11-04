package ar.nahuel.bitacora.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class PostgrestFilterTest {
    @Test
    fun quotesAndEscapesValues() {
        assertEquals("in.(\"a\",\"sh:1:0\")", PostgrestFilter.inList(listOf("a", "sh:1:0")))
        assertEquals("in.(\"x\\\"),y\")", PostgrestFilter.inList(listOf("x\"),y")))
    }
}
