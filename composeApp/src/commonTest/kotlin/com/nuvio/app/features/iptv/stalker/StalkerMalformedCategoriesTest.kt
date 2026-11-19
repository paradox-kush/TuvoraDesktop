package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A portal reply that is not a JSON array must reach the hub as a failure (Retry), not as "nothing here". */
class StalkerMalformedCategoriesTest {
    private var vodReply = """{"js":{"error":"weird"}}"""

    private val fakePortal: suspend (String, Map<String, String>) -> String = { url, _ ->
        val action = Regex("action=([^&]+)").find(url)?.groupValues?.get(1)
        val type = Regex("type=([^&]+)").find(url)?.groupValues?.get(1)
        when {
            action == "handshake" -> """{"js":{"token":"T"}}"""
            action == "get_profile" -> """{"js":{"id":"1","status":0}}"""
            action == "get_modules" -> """{"js":{}}"""
            type == "vod" && action == "get_categories" -> vodReply
            else -> """{"js":[]}"""
        }
    }

    private fun account() = XtreamAccount(
        id = "stalker|malformed-${kotlin.random.Random.nextLong()}", name = "Portal", baseUrl = "http://portal.test/stalker_portal/c/",
        username = "", password = "", sourceType = "stalker", macAddress = "00:1A:79:00:00:03",
    )

    @BeforeTest
    fun setUp() {
        StalkerClient.sessionFactory = { StalkerSession(it, fakePortal, { u, h, c -> c(fakePortal(u, h)) }) }
    }

    @AfterTest
    fun tearDown() {
        StalkerClient.sessionFactory = { StalkerSession(it) }
        StalkerClient.clearMemoryCachesForTest()
    }

    @Test
    fun `a non-array vod categories reply is a failure`() = runBlocking {
        val r = StalkerClient.vodCategories(account())
        assertTrue(r.isFailure, "a broken reply must not read as an empty section")
        assertTrue(r.exceptionOrNull() is StalkerMalformedReplyException)
    }

    @Test
    fun `a valid array reply still loads`() = runBlocking {
        vodReply = """{"js":[{"id":"3","title":"Films"}]}"""
        assertEquals(listOf("3"), StalkerClient.vodCategories(account()).getOrThrow().map { it.id })
    }
}
