package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.stalker.StalkerEmptyReplyPolicy.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B02: an empty reply after a fresh handshake is named by its shape, not always "in use elsewhere". */
class StalkerEmptyReplyPolicyTest {

    private val series = mapOf("type" to "series", "action" to "get_categories")

    @Test
    fun `an empty body is the other device`() {
        assertEquals(Kind.HELD_ELSEWHERE, StalkerEmptyReplyPolicy.classify(null))
        assertEquals(Kind.HELD_ELSEWHERE, StalkerEmptyReplyPolicy.classify("  \n"))
        assertTrue(StalkerEmptyReplyPolicy.startsCooldown(Kind.HELD_ELSEWHERE))
        assertTrue(StalkerEmptyReplyPolicy.message(Kind.HELD_ELSEWHERE, "P", series, null).contains("in use elsewhere"))
    }

    @Test
    fun `an empty envelope is a section the portal does not offer`() {
        for (body in listOf("""{"js":null}""", """{"js":false}""", """{"js":{}}""", """{}""")) {
            assertEquals(Kind.NOTHING_FOR_SECTION, StalkerEmptyReplyPolicy.classify(body), body)
        }
        assertFalse(StalkerEmptyReplyPolicy.startsCooldown(Kind.NOTHING_FOR_SECTION))
        val msg = StalkerEmptyReplyPolicy.message(Kind.NOTHING_FOR_SECTION, "My portal", series, """{"js":null}""")
        assertTrue(msg.contains("Series"), msg)
        assertFalse(msg.contains("elsewhere"), msg)
    }

    @Test
    fun `an html page is a portal error quoted without secrets`() {
        val body = "<html><body><b>Fatal error</b>: Uncaught exception in /var/www/stalker_portal/server/lib/vod.class.php " +
            "token=ABCDEF0123456789ABCDEF0123456789 mac=00:1A:79:58:B3:A6</body></html>"
        assertEquals(Kind.PORTAL_ERROR, StalkerEmptyReplyPolicy.classify(body))
        assertFalse(StalkerEmptyReplyPolicy.startsCooldown(Kind.PORTAL_ERROR))
        val msg = StalkerEmptyReplyPolicy.message(Kind.PORTAL_ERROR, "P", series, body)
        assertTrue(msg.contains("Fatal error"), msg)
        assertFalse(msg.contains("<b>"), msg)
        assertFalse(msg.contains("ABCDEF0123456789"), msg)
        assertFalse(msg.contains("58:B3:A6"), msg)
    }

    @Test
    fun `the excerpt redacts and caps`() {
        assertEquals("mac=<redacted> ok", StalkerEmptyReplyPolicy.excerpt("mac=00%3A1A%3A79%3A58%3AB3%3AA6 ok"))
        assertEquals("<mac> seen", StalkerEmptyReplyPolicy.excerpt("00:1A:79:58:B3:A6 seen"))
        assertEquals("password=<redacted>&x=1", StalkerEmptyReplyPolicy.excerpt("password=hunter2&x=1"))
        val long = StalkerEmptyReplyPolicy.excerpt("word ".repeat(100), max = 20)
        assertTrue(long.length <= 20 && long.endsWith("…"), long)
    }
}
