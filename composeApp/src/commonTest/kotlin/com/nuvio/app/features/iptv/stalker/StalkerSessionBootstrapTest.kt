package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.iptv.XtreamAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Session-level bootstrap contract against a scripted fake portal (the injectable httpGet seam):
 *
 *  - `auth_second_step`: the portal's own client sends get_profile with auth_second_step=0, and
 *    ONLY the post-do_auth retry sets auth_second_step=1 (`c/xpcom.common.js`: do_auth success on a
 *    status=2 profile → `stb.get_user_profile(true)`).
 *  - a get_profile `status: 1` is a refusal — device-conflict phrasings get the actionable error,
 *    everything else the generic one; a bare `{status: 1}` is never a success.
 *  - the watchdog init ping (`type=watchdog&action=get_events&init=1`, `c/watchdog.js`) fires as
 *    part of session activation, and its failure never fails auth.
 */
class StalkerSessionBootstrapTest {

    private val requestedUrls = mutableListOf<String>()
    private var session: StalkerSession? = null

    @AfterTest
    fun tearDown() {
        session?.shutdown()
        session = null
    }

    private fun param(url: String, key: String): String? =
        Regex("[?&]$key=([^&]*)").find(url)?.groupValues?.get(1)

    private fun action(url: String) = param(url, "action")

    /** Scripted portal: body per action, with an optional per-request override hook. */
    private fun portal(
        profileBody: (authSecondStep: String?) -> String,
        doAuthBody: String = """{"js":true}""",
        getEventsBody: String = """{"js":{"data":{"msgs":0}}}""",
        getEventsThrows: Boolean = false,
    ): suspend (String, Map<String, String>) -> String = { url, _ ->
        requestedUrls += url
        when (action(url)) {
            "handshake" -> """{"js":{"token":"T"}}"""
            "get_profile" -> profileBody(param(url, "auth_second_step"))
            "do_auth" -> doAuthBody
            "get_events" -> if (getEventsThrows) throw IllegalStateException("boom") else getEventsBody
            "get_genres" -> """{"js":[{"id":"1","title":"Sports"}]}"""
            else -> """{"js":[]}"""
        }
    }

    private fun account(withCredentials: Boolean = false) = XtreamAccount(
        id = "boot", name = "portal", baseUrl = "http://portal.test",
        username = "", password = "", sourceType = "stalker",
        macAddress = "00:1A:79:58:B3:A6",
        stalkerUsername = if (withCredentials) "user" else null,
        stalkerPassword = if (withCredentials) "pass" else null,
    )

    private fun startSession(fake: suspend (String, Map<String, String>) -> String): StalkerSession =
        StalkerSession(account(withCredentials = true), fake).also { session = it }

    @Test
    fun `do_auth is followed by a profile retry carrying auth second step`() = runBlocking {
        val fake = portal(profileBody = { step ->
            if (step == "1") """{"js":{"id":"3","status":0,"watchdog_timeout":120}}"""
            else """{"js":{"id":"3","status":2,"auth_access":true}}"""
        })
        startSession(fake).request(mapOf("type" to "itv", "action" to "get_genres"))

        val profileSteps = requestedUrls.filter { action(it) == "get_profile" }.map { param(it, "auth_second_step") }
        assertEquals(listOf("0", "1"), profileSteps, "initial profile then exactly one second-step retry")
        val doAuthIdx = requestedUrls.indexOfFirst { action(it) == "do_auth" }
        val secondStepIdx = requestedUrls.indexOfFirst { action(it) == "get_profile" && param(it, "auth_second_step") == "1" }
        assertTrue(doAuthIdx in 1 until secondStepIdx, "the second-step profile must come AFTER do_auth")
    }

    @Test
    fun `a healthy profile never sends auth second step one`() = runBlocking {
        val fake = portal(profileBody = { """{"js":{"id":"1","status":0,"watchdog_timeout":120}}""" })
        startSession(fake).request(mapOf("type" to "itv", "action" to "get_genres"))

        assertEquals(
            listOf("0"),
            requestedUrls.filter { action(it) == "get_profile" }.map { param(it, "auth_second_step") },
            "only the post-do_auth retry may set auth_second_step=1"
        )
        assertEquals(0, requestedUrls.count { action(it) == "do_auth" })
    }

    @Test
    fun `a device conflict refusal raises the actionable error`() = runBlocking {
        val fake = portal(profileBody = { """{"js":{"status":1,"msg":"Device ID mismatch"}}""" })
        val e = assertFailsWith<StalkerDeviceConflictException> {
            startSession(fake).request(mapOf("type" to "itv", "action" to "get_genres"))
        }
        assertTrue(e.message.orEmpty().contains("nother device"), "message must name the other device: ${e.message}")
    }

    @Test
    fun `a bare status one profile is a refusal not a success`() = runBlocking {
        val fake = portal(profileBody = { """{"js":{"status":1}}""" })
        val e = assertFailsWith<StalkerPortalRefusedException> {
            startSession(fake).request(mapOf("type" to "itv", "action" to "get_genres"))
        }
        assertTrue(e !is StalkerDeviceConflictException, "no message means no device-conflict evidence")
    }

    @Test
    fun `an unrelated refusal message stays generic`() = runBlocking {
        val fake = portal(profileBody = { """{"js":{"status":1,"block_msg":"Your STB is <br/>damaged."}}""" })
        val e = assertFailsWith<StalkerPortalRefusedException> {
            startSession(fake).request(mapOf("type" to "itv", "action" to "get_genres"))
        }
        assertTrue(e !is StalkerDeviceConflictException)
        assertTrue(e.message.orEmpty().contains("Your STB is damaged."), "portal's own words surface: ${e.message}")
    }

    @Test
    fun `session activation sends one watchdog init ping before content`() = runBlocking {
        val fake = portal(profileBody = { """{"js":{"id":"1","status":0,"watchdog_timeout":120,"timeslot":30}}""" })
        startSession(fake).request(mapOf("type" to "itv", "action" to "get_genres"))

        val pings = requestedUrls.filter { param(it, "type") == "watchdog" && action(it) == "get_events" }
        assertEquals(1, pings.size, "exactly one init ping at activation")
        assertEquals("1", param(pings.single(), "init"))
        assertEquals("0", param(pings.single(), "cur_play_type"))
        assertEquals("0", param(pings.single(), "event_active_id"))
        val pingIdx = requestedUrls.indexOfFirst { action(it) == "get_events" }
        val genresIdx = requestedUrls.indexOfFirst { action(it) == "get_genres" }
        val profileIdx = requestedUrls.indexOfFirst { action(it) == "get_profile" }
        assertTrue(pingIdx in (profileIdx + 1) until genresIdx, "ping rides activation: after profile and before content")
    }

    @Test
    fun `a failed init ping never fails auth`() = runBlocking {
        val fake = portal(
            profileBody = { """{"js":{"id":"1","status":0,"watchdog_timeout":120}}""" },
            getEventsThrows = true,
        )
        val genres = startSession(fake).request(mapOf("type" to "itv", "action" to "get_genres"))
        assertTrue(genres.toString().contains("Sports"), "content still served after a ping failure")
    }
}
