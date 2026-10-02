package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The code lives in memory only: cleared on demand and 30 minutes after being set. */
class SetupCodeHolderTest {
    private var now = 1_000_000L
    private val holder = SetupCodeHolder(clock = { now })

    @Test
    fun `a valid code is held in its normalized form`() {
        assertTrue(holder.set("tuv-abcd-efgh-jkmn"))
        assertEquals("ABCDEFGHJKMN", holder.peek())
        assertTrue(holder.hasCode())
    }

    @Test
    fun `an invalid code holds nothing`() {
        holder.set("ABCDEFGHJKMN")
        assertFalse(holder.set("nope"))
        assertNull(holder.peek())
    }

    @Test
    fun `clear forgets the code`() {
        holder.set("ABCDEFGHJKMN")
        holder.clear()
        assertNull(holder.peek())
    }

    @Test
    fun `the code is forgotten 30 minutes after being set`() {
        holder.set("ABCDEFGHJKMN")
        now += 30 * 60_000L - 1
        assertEquals("ABCDEFGHJKMN", holder.peek(), "still held a moment before the limit")
        now += 1
        assertNull(holder.peek(), "gone at 30 minutes")
        assertNull(holder.peek(), "and stays gone")
    }

    @Test
    fun `setting the code again restarts the clock`() {
        holder.set("ABCDEFGHJKMN")
        now += 20 * 60_000L
        holder.set("ABCDEFGHJKMN")
        now += 20 * 60_000L
        assertEquals("ABCDEFGHJKMN", holder.peek())
    }
}
