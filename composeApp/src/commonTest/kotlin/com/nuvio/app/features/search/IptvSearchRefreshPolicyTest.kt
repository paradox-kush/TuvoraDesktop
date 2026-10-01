package com.nuvio.app.features.search

import com.nuvio.app.features.search.IptvSearchRefreshPolicy.Action
import com.nuvio.app.features.search.IptvSearchRefreshPolicy.RequestKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** UX15: what Search does when a request differs from the shown one only in IPTV's source set. */
class IptvSearchRefreshPolicyTest {

    private val shown = RequestKey(search = "matrix|xtream=true|addons", iptvSignature = "sig-A")

    @Test
    fun `changed IPTV settings refresh only the IPTV rows`() {
        assertEquals(
            Action.REFRESH_IPTV_ROWS,
            IptvSearchRefreshPolicy.decide(shown.copy(iptvSignature = "sig-B"), shown, forceRefresh = false),
            "only the IPTV lane re-runs, no add-on refetch",
        )
    }

    @Test
    fun `an unchanged request is reused`() {
        assertEquals(Action.REUSE, IptvSearchRefreshPolicy.decide(shown.copy(), shown, forceRefresh = false))
    }

    @Test
    fun `a new query or nothing shown runs the whole search`() {
        assertEquals(Action.RUN_SEARCH, IptvSearchRefreshPolicy.decide(shown.copy(search = "bbc|xtream=true|addons"), shown, false))
        assertEquals(Action.RUN_SEARCH, IptvSearchRefreshPolicy.decide(shown, null, forceRefresh = false))
        assertEquals(Action.RUN_SEARCH, IptvSearchRefreshPolicy.decide(shown, shown, forceRefresh = true))
    }

    @Test
    fun `the IPTV lane appearing or disappearing runs the whole search`() {
        val noIptv = RequestKey(search = shown.search, iptvSignature = null)
        assertEquals(Action.RUN_SEARCH, IptvSearchRefreshPolicy.decide(shown, noIptv, false), "first playlist enabled")
        assertEquals(Action.RUN_SEARCH, IptvSearchRefreshPolicy.decide(noIptv, shown, false), "last playlist disabled")
        assertEquals(Action.REUSE, IptvSearchRefreshPolicy.decide(noIptv, noIptv.copy(), false), "still no IPTV")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a burst of toggles is one refresh tick`() = runTest {
        val signatures = MutableSharedFlow<String?>()
        val ticks = mutableListOf<String?>()
        val job = launch { IptvSearchRefreshPolicy.refreshTicks(signatures, debounceMs = 500).toList(ticks) }
        advanceUntilIdle()

        signatures.emit("sig-A")
        advanceTimeBy(600)
        signatures.emit("sig-B")
        advanceTimeBy(100)
        signatures.emit("sig-C")
        advanceTimeBy(100)
        signatures.emit("sig-C")
        advanceTimeBy(600)
        signatures.emit("sig-C")
        advanceTimeBy(600)

        assertEquals(listOf<String?>("sig-A", "sig-C"), ticks, "settled values only, never a repeat")
        job.cancel()
    }
}
