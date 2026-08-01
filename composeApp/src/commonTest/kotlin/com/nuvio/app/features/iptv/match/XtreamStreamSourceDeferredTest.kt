package com.nuvio.app.features.iptv.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deferred-link scheme that keeps browsing free of create_link calls.
 *
 * Listing a matched Stalker title used to mint a play link per edition (up to 5 per account), which
 * spends the line's connection budget before the viewer has chosen anything — on a max_connections=1
 * line that is enough to make the real playback come back 401. Sources are now listed with this
 * marker and the link is minted on selection.
 *
 * The parsing is the fragile part: a Stalker account id is ITSELF pipe-delimited
 * ("stalker|http://host|MAC"), so a naive split picks the wrong account — or none.
 */
class XtreamStreamSourceDeferredTest {

    private val stalkerAccountId = "stalker|http://portal.example|00:1A:79:58:B3:A6"

    @Test
    fun `only deferred urls are recognised`() {
        assertTrue(XtreamStreamSource.isDeferred("stalker-deferred:acct|movie|17559|The Prestige"))
        assertFalse(XtreamStreamSource.isDeferred("http://panel.example/movie/u/p/1.mkv"))
        assertFalse(XtreamStreamSource.isDeferred(null))
        assertFalse(XtreamStreamSource.isDeferred(""))
    }

    @Test
    fun `movie round-trips through a pipe-riddled stalker account id`() {
        val parsed = XtreamStreamSource.parseDeferred(
            "stalker-deferred:$stalkerAccountId|movie|17559|The Prestige (2006)",
        )!!
        assertEquals(stalkerAccountId, parsed.accountId)
        assertTrue(parsed.isMovie)
        assertEquals(17559, parsed.a)
        assertEquals("The Prestige (2006)", parsed.name)
    }

    @Test
    fun `episode carries series season and episode`() {
        val parsed = XtreamStreamSource.parseDeferred(
            "stalker-deferred:$stalkerAccountId|episode|8842|3|7",
        )!!
        assertEquals(stalkerAccountId, parsed.accountId)
        assertFalse(parsed.isMovie)
        assertEquals(8842, parsed.a)
        assertEquals(3, parsed.b)
        assertEquals(7, parsed.c)
    }

    @Test
    fun `malformed urls yield null instead of a wrong account`() {
        assertNull(XtreamStreamSource.parseDeferred("stalker-deferred:no-kind-marker"))
        assertNull(XtreamStreamSource.parseDeferred("stalker-deferred:movie|1"))          // no account id
        assertNull(XtreamStreamSource.parseDeferred("stalker-deferred:acct|movie|abc"))   // non-numeric id
        assertNull(XtreamStreamSource.parseDeferred("stalker-deferred:acct|episode|1|2")) // missing episode
        assertNull(XtreamStreamSource.parseDeferred("http://real.example/x.mkv"))
    }
}
