package com.nuvio.app.features.iptv

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guide's per-channel EPG resolution ladder: manual mapping → provider rows if they pass the
 * sanity gate → mirror → none, with the answering rung remembered per (account, channel).
 *
 * The gate exists because present-but-garbage used to beat absent: wa12's skewed short-EPG rows
 * (nothing bracketing now — every epoch one zone-offset in the future) suppressed the mirror
 * entirely under the old `.ifEmpty` fallback and rendered a fully empty visible guide.
 */
class EpgSourceLadderTest {

    /** 2026-08-15 13:20:00 UTC, in ms — an arbitrary honest "now" for the fixtures. */
    private val now = 1_786_800_000_000L
    private val hour = 3_600_000L

    private fun programme(startMs: Long, endMs: Long, title: String) =
        XtreamProgram(title = title, description = "", startMs = startMs, endMs = endMs, nowPlaying = false)

    /** An honest response: the airing programme brackets now, upcoming rows follow. */
    private fun saneRows() = listOf(
        programme(now - 20 * 60_000L, now + 40 * 60_000L, "airing"),
        programme(now + 40 * 60_000L, now + 100 * 60_000L, "next"),
    )

    /**
     * The pre-Fix-2 wa12 shape: contiguous rows, every one shifted a zone offset into the future,
     * the first "upcoming" row starting +1.43 h out — nothing brackets now.
     */
    private fun wa12Rows(): List<XtreamProgram> {
        val firstStart = now + (143 * hour) / 100
        return listOf(
            programme(firstStart, firstStart + hour, "shifted airing"),
            programme(firstStart + hour, firstStart + 2 * hour, "shifted next"),
            programme(firstStart + 2 * hour, firstStart + 3 * hour, "shifted later"),
        )
    }

    private fun mirrorRows() = listOf(
        programme(now - 30 * 60_000L, now + 30 * 60_000L, "mirror airing"),
        programme(now + 30 * 60_000L, now + 90 * 60_000L, "mirror next"),
    )

    // --- the ladder --------------------------------------------------------------------------

    @Test
    fun `the ladder prefers sane provider rows`() = runBlocking {
        var mirrorAsked = false
        val resolution = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { saneRows() },
            mirror = { mirrorAsked = true; mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.PROVIDER, resolution.source)
        assertEquals(saneRows(), resolution.programmes)
        assertFalse(mirrorAsked, "sane provider rows must not cost a mirror read")
    }

    @Test
    fun `garbage provider rows fall to the mirror`() = runBlocking {
        val resolution = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { wa12Rows() },
            mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.MIRROR, resolution.source)
        assertEquals(mirrorRows(), resolution.programmes)
    }

    /** Regression pin of today's `.ifEmpty { mirror }` behavior: empty fails the gate trivially. */
    @Test
    fun `an empty provider response falls to the mirror`() = runBlocking {
        val resolution = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { emptyList() },
            mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.MIRROR, resolution.source)
        assertEquals(mirrorRows(), resolution.programmes)
    }

    @Test
    fun `the mirror answering nothing leaves the channel empty`() = runBlocking {
        val resolution = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { wa12Rows() },
            mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.NONE, resolution.source)
        assertEquals(emptyList(), resolution.programmes)
    }

    @Test
    fun `a manual resolver answer wins every rung`() = runBlocking {
        var providerAsked = false
        val manualRows = listOf(programme(now - hour, now + hour, "user mapped"))
        val resolution = EpgSourceLadder.resolveAndRemember(
            memory = EpgSourceLadder.Memory(),
            accountId = "acc",
            streamId = 7,
            nowMs = now,
            manual = { _, _, _ -> manualRows },
            provider = { providerAsked = true; saneRows() },
            mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.MANUAL, resolution.source)
        assertEquals(manualRows, resolution.programmes)
        assertFalse(providerAsked, "a manual mapping must answer before the panel is contacted")
    }

    /** The seam's fall-through contract: an unmapped channel (null) takes the automatic rungs. */
    @Test
    fun `an unmapped manual channel falls through to the automatic rungs`() = runBlocking {
        val resolution = EpgSourceLadder.resolve(
            nowMs = now,
            manual = { null },
            provider = { saneRows() },
            mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.PROVIDER, resolution.source)
    }

    // --- the per-channel source memory ---------------------------------------------------------

    @Test
    fun `the chosen source is remembered per channel`() = runBlocking {
        val memory = EpgSourceLadder.Memory()
        // Channel 1's panel rows are garbage: it falls to the mirror and that is remembered.
        EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 1, nowMs = now,
            provider = { wa12Rows() }, mirror = { mirrorRows() },
        )
        // Channel 2's panel is honest: it stays on the provider.
        EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 2, nowMs = now,
            provider = { saneRows() }, mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.MIRROR, memory.rememberedFor("acc", 1))
        assertEquals(EpgSourceLadder.Source.PROVIDER, memory.rememberedFor("acc", 2))

        // The next focus on channel 1 goes straight to the mirror — no panel round-trip.
        var providerAsked = false
        val again = EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 1, nowMs = now,
            provider = { providerAsked = true; wa12Rows() }, mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.MIRROR, again.source)
        assertFalse(providerAsked, "a channel remembered as mirror-fed must not re-ask the panel")
    }

    /** The memory is a hint, not a cage: a mirror that stops answering falls back to the ladder. */
    @Test
    fun `a remembered mirror that dries up falls back through the full ladder`() = runBlocking {
        val memory = EpgSourceLadder.Memory()
        memory.remember("acc", 1, EpgSourceLadder.Source.MIRROR)
        val resolution = EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 1, nowMs = now,
            provider = { saneRows() }, mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.PROVIDER, resolution.source)
        assertEquals(EpgSourceLadder.Source.PROVIDER, memory.rememberedFor("acc", 1))
    }

    /** A transient panel failure must not pin a channel empty for the whole session. */
    @Test
    fun `a channel that resolved to nothing is retried on the next focus`() = runBlocking {
        val memory = EpgSourceLadder.Memory()
        EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 1, nowMs = now,
            provider = { emptyList() }, mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.NONE, memory.rememberedFor("acc", 1))
        // The panel recovered: the retry reaches it and the channel comes back.
        val recovered = EpgSourceLadder.resolveAndRemember(
            memory = memory, accountId = "acc", streamId = 1, nowMs = now,
            provider = { saneRows() }, mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.PROVIDER, recovered.source)
    }

    @Test
    fun `the memory stays bounded and forgets per account`() {
        val memory = EpgSourceLadder.Memory(cap = 3)
        for (id in 1..4) memory.remember("acc", id, EpgSourceLadder.Source.PROVIDER)
        assertNull(memory.rememberedFor("acc", 1), "the oldest entry is evicted at the cap")
        assertEquals(EpgSourceLadder.Source.PROVIDER, memory.rememberedFor("acc", 4))

        memory.remember("other", 9, EpgSourceLadder.Source.MIRROR)
        memory.forgetAccount("acc")
        assertNull(memory.rememberedFor("acc", 4))
        assertEquals(EpgSourceLadder.Source.MIRROR, memory.rememberedFor("other", 9))
    }

    /** What the settings coverage line reads: per-account counts of which rung answers. */
    @Test
    fun `the tally counts sources for one account only`() {
        val memory = EpgSourceLadder.Memory()
        memory.remember("acc", 1, EpgSourceLadder.Source.PROVIDER)
        memory.remember("acc", 2, EpgSourceLadder.Source.MIRROR)
        memory.remember("acc", 3, EpgSourceLadder.Source.MIRROR)
        memory.remember("acc", 4, EpgSourceLadder.Source.NONE)
        memory.remember("other", 5, EpgSourceLadder.Source.PROVIDER)
        val tally = memory.tally("acc")
        assertEquals(EpgSourceLadder.Tally(manual = 0, provider = 1, mirror = 2, none = 1), tally)
        assertEquals(4, tally.total)
    }

    // --- reporting the coverage split -----------------------------------------------------------
    //
    // epg_mapping counts what the MIRROR could match and says nothing about the panel's own EPG,
    // so "13% matched" was being read as "only 13% of my channels have a guide" when the two are
    // independent and overlapping. epg_resolve reports what each channel actually resolved to.
    //
    // The floor was 50 on a guess and the event never fired once in the field; these pin it to a
    // measured session instead.

    private fun tally(manual: Int = 0, provider: Int = 0, mirror: Int = 0, none: Int = 0) =
        EpgSourceLadder.Tally(manual, provider, mirror, none)

    @Test
    fun `a real browsing session reports`() {
        // The session that caught the bad threshold: an S24 resolved 19 channels and stopped.
        // At the old floor of 50 this reported nothing at all.
        val realSession = tally(provider = 6, mirror = 3, none = 10)
        assertEquals(19, realSession.total)
        assertTrue(
            EpgSourceLadder.shouldReport(realSession, lastReportedTotal = 0),
            "a normal browse must produce a report, or the event is decorative",
        )
    }

    @Test
    fun `a degenerate sample is still refused`() {
        // Three channels resolving MIRROR is not "100% mirror coverage".
        assertFalse(EpgSourceLadder.shouldReport(tally(mirror = 3), lastReportedTotal = 0))
    }

    @Test
    fun `the floor counts every source - not just the hits`() {
        val allNone = tally(none = EpgSourceLadder.MIN_REPORT_SAMPLE)
        assertTrue(
            EpgSourceLadder.shouldReport(allNone, lastReportedTotal = 0),
            "a playlist where nothing resolves is exactly the case worth reporting",
        )
    }

    @Test
    fun `a report waits for the sample to double`() {
        // Otherwise every extra channel in a long browse would be its own event.
        assertFalse(EpgSourceLadder.shouldReport(tally(none = 19), lastReportedTotal = 10))
        assertTrue(EpgSourceLadder.shouldReport(tally(none = 20), lastReportedTotal = 10))
    }

    @Test
    fun `a long browse reports a handful of times - not hundreds`() {
        var last = 0
        var reports = 0
        for (total in 1..1_000) {
            val t = tally(none = total)
            if (EpgSourceLadder.shouldReport(t, last)) { reports++; last = t.total }
        }
        assertTrue(reports in 2..12, "a thousand channels should yield a few samples, was $reports")
    }

    @Test
    fun `the memory tracks the sample size per account`() {
        val memory = EpgSourceLadder.Memory()
        assertEquals(0, memory.lastReportedTotal("acc"))
        memory.markReported("acc", 19)
        assertEquals(19, memory.lastReportedTotal("acc"))
        assertEquals(0, memory.lastReportedTotal("other"), "one account must not silence another")
    }

    @Test
    fun `forgetting an account lets its new split be reported`() {
        // A rebuilt mapping changes the answer, so the previously-reported split is stale.
        val memory = EpgSourceLadder.Memory()
        memory.remember("acc", 1, EpgSourceLadder.Source.MIRROR)
        memory.markReported("acc", 40)
        memory.forgetAccount("acc")
        assertEquals(0, memory.lastReportedTotal("acc"))
    }

    // --- the sanity gate ------------------------------------------------------------------------

    @Test
    fun `the gate tolerates a schedule boundary but not a zone skew`() {
        // A programme that ended moments ago (the guide asked mid-handover): still sane.
        val justEnded = listOf(programme(now - hour, now - 60_000L, "just ended"))
        assertTrue(EpgSourceLadder.providerPassesGate(justEnded, now))
        // The smallest real zone step (15 min) must already fail — slack far below it.
        val quarterOff = listOf(programme(now + 15 * 60_000L, now + 75 * 60_000L, "shifted"))
        assertFalse(EpgSourceLadder.providerPassesGate(quarterOff, now))
        assertFalse(EpgSourceLadder.providerPassesGate(emptyList(), now))
    }

    // ---- A failed panel ask is not a coverage fact (2026-08-18 field regression) ----------------
    //
    // An Onn 4K browsing the guide DURING a 76s mirror sync reported provider=0, none=68/80. An
    // S24 on the same account an hour later resolved 37% from that same panel. The panel was
    // fine — the box was saturated, every shortEpg call failed, and each failure was booked as
    // "this channel has no guide". Both the client and the call sites collapsed Result.failure
    // into emptyList(), so the ladder could not tell a timeout from an honest empty answer.

    @Test
    fun `a failed panel ask with no mirror is unavailable - not none`() = runBlocking {
        val r = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { null },          // the ask FAILED
            mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.UNAVAILABLE, r.source, "a timeout is not a coverage fact")
        assertTrue(r.programmes.isEmpty())
    }

    @Test
    fun `a panel that answers with nothing is still none`() = runBlocking {
        val r = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { emptyList() },   // the panel SPOKE, and had nothing
            mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.NONE, r.source, "an honest empty answer is coverage")
    }

    @Test
    fun `a failed panel ask still falls to the mirror`() = runBlocking {
        val r = EpgSourceLadder.resolve(
            nowMs = now,
            provider = { null },
            mirror = { saneRows() },
        )
        assertEquals(EpgSourceLadder.Source.MIRROR, r.source, "failure must not suppress the mirror")
    }

    @Test
    fun `a dried-up mirror plus a failed panel ask is unavailable`() = runBlocking {
        val r = EpgSourceLadder.resolve(
            nowMs = now,
            remembered = EpgSourceLadder.Source.MIRROR,
            provider = { null },
            mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.UNAVAILABLE, r.source, "the remembered branch counts too")
    }

    @Test
    fun `the tally keeps failures out of the none count`() {
        val memory = EpgSourceLadder.Memory()
        memory.remember("acct", 1, EpgSourceLadder.Source.NONE)
        memory.remember("acct", 2, EpgSourceLadder.Source.UNAVAILABLE)
        memory.remember("acct", 3, EpgSourceLadder.Source.UNAVAILABLE)
        memory.remember("acct", 4, EpgSourceLadder.Source.PROVIDER)
        val tally = memory.tally("acct")
        assertEquals(1, tally.none, "only the honest empty answer is none")
        assertEquals(2, tally.unavailable, "the two failures are counted apart")
        assertEquals(4, tally.total, "failures still count toward the sample size")
    }

    @Test
    fun `a failure is retried rather than pinned for the session`() = runBlocking {
        val memory = EpgSourceLadder.Memory()
        var asks = 0
        repeat(2) {
            EpgSourceLadder.resolveAndRemember(
                memory = memory,
                accountId = "acct",
                streamId = 7,
                nowMs = now,
                provider = { asks++; if (asks == 1) null else saneRows() },
                mirror = { emptyList() },
            )
        }
        assertEquals(2, asks, "a transient failure must not cool the channel for the whole session")
        assertEquals(
            EpgSourceLadder.Source.PROVIDER,
            memory.rememberedFor("acct", 7),
            "the retry's honest answer replaces the failure",
        )
    }

    // ---- P1: the account's own guide, stored once and read from SQLite ------------------------
    //
    // The design always said EPG is ingested into SQLite and read from there. That was true for the
    // mirror and M3U lanes and never for Xtream: resolveSource answered null for every Xtream
    // playlist, so ensureEpg no-opped and the guide asked the panel once per channel, forever.

    @Test
    fun `the store outranks the per-channel ask`() = runBlocking {
        var panelAsks = 0
        val r = EpgSourceLadder.resolve(
            nowMs = now,
            store = { saneRows() },
            provider = { panelAsks++; saneRows() },
            mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.STORE, r.source)
        assertEquals(0, panelAsks, "a stored guide must cost zero panel requests")
    }

    @Test
    fun `an empty store falls through to the panel`() = runBlocking {
        val r = EpgSourceLadder.resolve(
            nowMs = now,
            store = { emptyList() },          // ingest has not run, or this channel is unmatched
            provider = { saneRows() },
            mirror = { emptyList() },
        )
        assertEquals(EpgSourceLadder.Source.PROVIDER, r.source, "no stored guide must not break the old path")
    }

    @Test
    fun `a skewed store is gated exactly like the panel`() = runBlocking {
        val r = EpgSourceLadder.resolve(
            nowMs = now,
            store = { wa12Rows() },         // same panel, same lie, cheaper transport
            provider = { emptyList() },
            mirror = { mirrorRows() },
        )
        assertEquals(EpgSourceLadder.Source.MIRROR, r.source, "the store is not trusted more than the panel")
    }

    @Test
    fun `the tally counts store separately from provider`() {
        val memory = EpgSourceLadder.Memory()
        memory.remember("acct", 1, EpgSourceLadder.Source.STORE)
        memory.remember("acct", 2, EpgSourceLadder.Source.STORE)
        memory.remember("acct", 3, EpgSourceLadder.Source.PROVIDER)
        val tally = memory.tally("acct")
        assertEquals(2, tally.store, "zero-network resolutions are their own number")
        assertEquals(1, tally.provider)
        assertEquals(3, tally.total)
    }
}
