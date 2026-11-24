package com.nuvio.app.core.telemetry.internal

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `first_leave` on Desktop is captured when the app quits, so it must ride the bounded synchronous exit POST. */
class DesktopFirstLeaveExitTest {
    private var http: HttpServer? = null
    private val bodies = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun reset() {
        DesktopSyncSender.shuttingDown = true
        DesktopSyncSender.exitSendFailed = false
    }

    @AfterTest
    fun restore() {
        http?.stop(0)
        DesktopSyncSender.shuttingDown = false
        DesktopSyncSender.exitSendFailed = false
        DesktopSyncSender.endpoint = "https://us.i.posthog.com/batch/"
    }

    private fun startServer(): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            bodies += String(ex.requestBody.readBytes(), Charsets.UTF_8)
            val reply = """{"status":1}""".toByteArray()
            ex.sendResponseHeaders(200, reply.size.toLong())
            ex.responseBody.use { it.write(reply) }
        }
        s.start()
        http = s
        return "http://127.0.0.1:${s.address.port}/batch/"
    }

    @Test
    fun onlyTheFirstLeaveStepOfTheFunnelAndSessionHealthGoOutSynchronouslyAtExit() {
        assertTrue(DesktopSyncSender.sendsSynchronouslyAtExit("first_run_step", mapOf("step" to "first_leave")), "first_leave")
        assertFalse(DesktopSyncSender.sendsSynchronouslyAtExit("first_run_step", mapOf("step" to "first_launch")), "other steps keep the SDK queue")
        assertFalse(DesktopSyncSender.sendsSynchronouslyAtExit("first_run_step", emptyMap()), "no step")
        assertTrue(DesktopSyncSender.sendsSynchronouslyAtExit("session_health", emptyMap()), "session_health")
        assertFalse(DesktopSyncSender.sendsSynchronouslyAtExit("screen_view", mapOf("step" to "first_leave")), "other events")
    }

    @Test
    fun firstLeaveIsDeliveredBeforeTheExitSendReturns() {
        DesktopSyncSender.endpoint = startServer()
        val props = mapOf("step" to "first_leave", "last_screen" to "home", "secs_in_app" to 42)
        val ok = DesktopSyncSender.send("first_run_step", props, emptyMap(), "install-1", null)
        assertTrue(ok, "the POST was accepted")
        assertEquals(1, bodies.size, "one request reached the server")
        assertTrue(bodies[0].contains("first_run_step") && bodies[0].contains("first_leave"), "the body carries the event: ${bodies[0]}")
    }

    @Test
    fun afterOneFailedExitSendTheRestSkipTheNetworkSoAQuitCostsOneTimeoutAtMost() {
        DesktopSyncSender.endpoint = "http://127.0.0.1:1/batch/"
        assertFalse(DesktopSyncSender.send("session_health", emptyMap(), emptyMap(), "install-1", null), "dead endpoint")
        assertTrue(DesktopSyncSender.exitSendFailed, "the failure is remembered")
        DesktopSyncSender.endpoint = startServer()
        val started = System.currentTimeMillis()
        assertFalse(DesktopSyncSender.send("first_run_step", mapOf("step" to "first_leave"), emptyMap(), "install-1", null), "skipped")
        assertEquals(0, bodies.size, "no request was made after the failure")
        assertTrue(System.currentTimeMillis() - started < 500, "skipping is immediate")
    }
}
