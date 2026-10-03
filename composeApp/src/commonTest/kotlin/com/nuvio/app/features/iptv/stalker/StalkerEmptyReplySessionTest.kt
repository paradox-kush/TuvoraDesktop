package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B02 at the session: "Series → the session is in use elsewhere" was the label for ANY empty reply
 * after a re-handshake. A portal that answers a well-formed envelope with nothing (genuine Ministra
 * has no `type=series`) must say so — and must not trip the two-device cooldown, which made every
 * other empty-first call for 30 s fail with "held by another device".
 */
class StalkerEmptyReplySessionTest {

    private var session: StalkerSession? = null

    @AfterTest
    fun tearDown() {
        session?.shutdown()
    }

    private fun action(url: String) = Regex("[?&]action=([^&]*)").find(url)?.groupValues?.get(1)
    private fun type(url: String) = Regex("[?&]type=([^&]*)").find(url)?.groupValues?.get(1)

    private fun start(seriesBody: String): StalkerSession {
        val fake: suspend (String, Map<String, String>) -> String = { url, _ ->
            when {
                action(url) == "handshake" -> """{"js":{"token":"T"}}"""
                action(url) == "get_profile" -> """{"js":{"id":"1","status":0}}"""
                type(url) == "series" -> seriesBody
                else -> """{"js":[]}"""
            }
        }
        return StalkerSession(
            XtreamAccount(
                id = "b02", name = "My portal", baseUrl = "http://portal.test", username = "", password = "",
                sourceType = "stalker", macAddress = "00:1A:79:58:B3:A6",
            ),
            fake,
        ).also { session = it }
    }

    private val seriesCategories = mapOf("type" to "series", "action" to "get_categories")

    @Test
    fun `an empty envelope names the section and does not blame another device`() = runBlocking {
        val s = start("""{"js":null}""")
        val first = assertFailsWith<StalkerSessionUnavailableException> { s.request(seriesCategories) }
        assertTrue(first.message.orEmpty().contains("Series"), first.message)
        assertFalse(first.message.orEmpty().contains("elsewhere"), first.message)
        // No two-device cooldown: the next call is judged on its own reply again.
        val second = assertFailsWith<StalkerSessionUnavailableException> { s.request(seriesCategories) }
        assertFalse(second.message.orEmpty().contains("cooling down"), second.message)
    }

    @Test
    fun `an error page is quoted`() = runBlocking {
        val s = start("<html><body>Fatal error: unknown module series</body></html>")
        val e = assertFailsWith<StalkerSessionUnavailableException> { s.request(seriesCategories) }
        assertTrue(e.message.orEmpty().contains("Fatal error: unknown module series"), e.message)
    }
}
