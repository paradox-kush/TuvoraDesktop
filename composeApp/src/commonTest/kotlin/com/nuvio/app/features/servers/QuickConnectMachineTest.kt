package com.nuvio.app.features.servers

import com.nuvio.app.features.servers.QuickConnectEvent as E
import com.nuvio.app.features.servers.QuickConnectState as S
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickConnectMachineTest {
    private val waiting = S.Waiting(code = "123456", startedAtMs = 1_000L, attempt = 0, failures = 0)

    private fun reduce(state: S, event: E) = QuickConnectMachine.reduce(state, event)

    @Test
    fun `start issues a code and waits`() {
        assertEquals(S.Starting, reduce(S.Idle, E.Start), "idle to starting")
        assertEquals(waiting, reduce(S.Starting, E.CodeIssued("123456", 1_000L)), "code issued")
    }

    @Test
    fun `a pending poll keeps waiting with the next attempt`() {
        assertEquals(waiting.copy(attempt = 1), reduce(waiting, E.PollPending(2_000L)), "pending")
    }

    @Test
    fun `approval ends in authorised`() {
        assertEquals(S.Authorised, reduce(waiting, E.PollAuthorised), "authorised")
    }

    @Test
    fun `the server forgetting the request expires it`() {
        assertEquals(S.Expired, reduce(waiting, E.PollExpired), "404 from the server")
    }

    @Test
    fun `the ten minute deadline expires even while polls stay pending`() {
        val late = 1_000L + QuickConnectPolicy.REQUEST_LIFETIME_MS
        assertEquals(S.Expired, reduce(waiting, E.PollPending(late)), "pending at the deadline")
        assertEquals(S.Expired, reduce(waiting, E.Tick(late)), "tick at the deadline")
        assertEquals(waiting, reduce(waiting, E.Tick(late - 1)), "tick before it")
    }

    @Test
    fun `transient failures are tolerated up to the limit then fail`() {
        var state: S = waiting
        repeat(QuickConnectPolicy.MAX_CONSECUTIVE_FAILURES - 1) { state = reduce(state, E.PollFailed(2_000L, transient = true)) }
        assertTrue(state is S.Waiting, "still waiting")
        state = reduce(state, E.PollFailed(2_000L, transient = true))
        assertEquals(S.Failed(QuickConnectFailure.UNREACHABLE), state, "gave up")
        assertEquals(waiting.copy(attempt = 1), reduce(waiting.copy(failures = 3), E.PollPending(2_000L)), "a good poll resets the count")
    }

    @Test
    fun `a refused request means quick connect is disabled`() {
        assertEquals(S.Failed(QuickConnectFailure.DISABLED), reduce(waiting, E.PollFailed(2_000L, transient = false)), "401")
        assertEquals(S.Failed(QuickConnectFailure.DISABLED), reduce(S.Starting, E.StartFailed(QuickConnectFailure.DISABLED)), "start refused")
    }

    @Test
    fun `retry restarts from every terminal state and cancel returns to idle`() {
        listOf(S.Expired, S.Failed(QuickConnectFailure.UNREACHABLE), S.Authorised).forEach {
            assertEquals(S.Starting, reduce(it, E.Start), "retry from $it")
        }
        assertEquals(waiting, reduce(waiting, E.Start), "a running request is not restarted")
        assertEquals(S.Idle, reduce(waiting, E.Cancel), "cancel")
    }

    @Test
    fun `terminal states ignore late poll results`() {
        assertEquals(S.Expired, reduce(S.Expired, E.PollAuthorised), "expired stays")
        assertEquals(S.Authorised, reduce(S.Authorised, E.PollExpired), "authorised stays")
    }

    @Test
    fun `the poll cadence is bounded and only runs while waiting`() {
        assertEquals(QuickConnectPolicy.FIRST_POLL_MS, QuickConnectMachine.nextDelayMs(waiting), "first")
        assertEquals(QuickConnectPolicy.STEADY_POLL_MS, QuickConnectMachine.nextDelayMs(waiting.copy(attempt = 5)), "steady")
        assertNull(QuickConnectMachine.nextDelayMs(S.Expired), "no poll when expired")
        assertNull(QuickConnectMachine.nextDelayMs(S.Idle), "no poll when idle")
        assertFalse(QuickConnectMachine.isTerminal(waiting), "waiting is not terminal")
        assertTrue(QuickConnectMachine.isTerminal(S.Failed(QuickConnectFailure.DISABLED)), "failed is")
    }

    @Test
    fun `the code is shown grouped and typed codes are normalised`() {
        assertEquals("123 456", QuickConnectPolicy.displayCode("123456"), "grouped")
        assertEquals("9:41", QuickConnectPolicy.countdownLabel(9 * 60_000L + 41_000L), "countdown")
        assertEquals(0L, QuickConnectPolicy.remainingMs(0L, QuickConnectPolicy.REQUEST_LIFETIME_MS + 5), "never negative")
    }
}
