package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The hub's Favorites/Recent rails sit inside ONE provider's hub, but the stores behind them keep
 * a single flat profile-wide list spanning every playlist. Before this was scoped the rails showed
 * other providers' channels. Twin of the TV guide's rail-scope pins.
 */
class HubRailScopeTest {

    private val accA = "http://a.example|userA"
    private val accB = "http://b.example|userB"

    private fun idsAcross() = listOf(
        XtreamItemRegistry.liveId(accA, 11),
        XtreamItemRegistry.liveId(accB, 22),
        XtreamItemRegistry.liveId(accA, 33),
    )

    @Test
    fun `a rail keeps only the selected account channels`() {
        val kept = idsAcross().filter { it.startsWith(XtreamItemRegistry.accountPrefix(accA)) }
        assertEquals(2, kept.size, "only account A's two channels survive")
        assertEquals(
            listOf(XtreamItemRegistry.liveId(accA, 11), XtreamItemRegistry.liveId(accA, 33)),
            kept,
            "the surviving ids are A's",
        )
    }

    @Test
    fun `another account channels never leak into the rail`() {
        val kept = idsAcross().filter { it.startsWith(XtreamItemRegistry.accountPrefix(accB)) }
        assertEquals(1, kept.size, "only account B's single channel survives")
        assertEquals(XtreamItemRegistry.liveId(accB, 22), kept.single())
    }

    /**
     * Account ids are "baseUrl|username", so one can literally prefix another. The trailing
     * separator in accountPrefix is what keeps the shorter account from swallowing the longer one.
     */
    @Test
    fun `an account id that prefixes another does not swallow its channels`() {
        val shortAcc = "http://a.example|user"
        val longAcc = "http://a.example|user2"
        val ids = listOf(XtreamItemRegistry.liveId(shortAcc, 1), XtreamItemRegistry.liveId(longAcc, 2))
        val kept = ids.filter { it.startsWith(XtreamItemRegistry.accountPrefix(shortAcc)) }
        assertEquals(1, kept.size, "the longer account's channel must not appear")
        assertEquals(XtreamItemRegistry.liveId(shortAcc, 1), kept.single())
    }
}
