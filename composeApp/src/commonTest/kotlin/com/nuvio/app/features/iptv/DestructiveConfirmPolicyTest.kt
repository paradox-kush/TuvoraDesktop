package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Contract section 7: type-to-confirm (phone/desktop) and hold-to-confirm (TV) golden tables. */
class DestructiveConfirmPolicyTest {

    private fun confirmed(action: DestructiveAction, typed: String) = DestructiveConfirmPolicy.isConfirmed(action, typed)

    @Test
    fun `the required words`() {
        assertEquals("DETACH", DestructiveConfirmPolicy.requiredWord(DestructiveAction.DETACH))
        assertEquals("REMOVE", DestructiveConfirmPolicy.requiredWord(DestructiveAction.REMOVE))
    }

    @Test
    fun `Detach is confirmed by the word and ignoring case and padding and accents`() {
        for (typed in listOf("DETACH", "detach", " Detach ", "détach", "DÉTÀCH", "détach", "ＤＥＴＡＣＨ")) {
            assertTrue(confirmed(DestructiveAction.DETACH, typed), "Detach `$typed`")
        }
    }

    @Test
    fun `Detach is not confirmed by anything else`() {
        for (typed in listOf("DETAC", "REMOVE", "", "   ", "DETACHH", "DE TACH", "detach it")) {
            assertFalse(confirmed(DestructiveAction.DETACH, typed), "Detach `$typed`")
        }
    }

    @Test
    fun `Remove is confirmed by its own word only`() {
        for (typed in listOf("remove", "REMOVE", " Remove\n")) assertTrue(confirmed(DestructiveAction.REMOVE, typed), "Remove `$typed`")
        for (typed in listOf("DETACH", "REMOV", "", "removed")) assertFalse(confirmed(DestructiveAction.REMOVE, typed), "Remove `$typed`")
    }

    @Test
    fun `Return confirms only when the word already matches`() {
        assertFalse(DestructiveConfirmPolicy.returnConfirms(DestructiveAction.REMOVE, ""))
        assertFalse(DestructiveConfirmPolicy.returnConfirms(DestructiveAction.REMOVE, "rem"))
        assertFalse(DestructiveConfirmPolicy.returnConfirms(DestructiveAction.DETACH, "REMOVE"))
        assertTrue(DestructiveConfirmPolicy.returnConfirms(DestructiveAction.REMOVE, "remove"))
    }

    @Test
    fun `stroked letters are not folded because NFKD does not decompose them`() {
        assertFalse(confirmed(DestructiveAction.DETACH, "ĐETACH"))
    }

    @Test
    fun `Detach copy names the provider and says what happens`() {
        val copy = DestructiveConfirmPolicy.copy(DestructiveAction.DETACH, playlistName = "Acme TV", providerName = "Acme")
        assertEquals("Detach from Acme?", copy.title)
        assertEquals(
            "You keep this playlist, but Acme can't update it any more. If their server changes you would need to update it yourself.",
            copy.message,
        )
        assertEquals(null, copy.extra)
    }

    @Test
    fun `Remove copy is the same for every playlist and adds the re-add warning only when managed`() {
        val plain = DestructiveConfirmPolicy.copy(DestructiveAction.REMOVE, playlistName = "My list")
        assertEquals("Remove My list?", plain.title)
        assertEquals("This deletes the playlist from this profile on all your devices.", plain.message)
        assertEquals(null, plain.extra)
        val managed = DestructiveConfirmPolicy.copy(DestructiveAction.REMOVE, "Acme TV", providerName = "Acme", managed = true)
        assertEquals("You may need a new code from Acme to get it back.", managed.extra)
    }

    @Test
    fun `hold to confirm golden table`() {
        val policy = HoldToConfirmPolicy()
        assertEquals(2000L, policy.holdMs)
        val table = listOf(
            0L to (0.0f to false), 1000L to (0.5f to false), 1999L to (0.9995f to false),
            2000L to (1.0f to true), 3000L to (1.0f to true),
        )
        for ((held, expected) in table) {
            assertEquals(expected.first, policy.progress(held), 0.0001f, "progress at $held ms")
            assertEquals(expected.second, policy.isConfirmed(held), "confirmed at $held ms")
        }
    }

    @Test
    fun `releasing early confirms nothing and a negative hold is zero`() {
        val policy = HoldToConfirmPolicy()
        assertFalse(policy.isConfirmed(150))
        assertEquals(0f, policy.progress(-5))
    }
}
