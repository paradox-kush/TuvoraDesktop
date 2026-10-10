package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.LivePlaybackStartupPolicy
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression (RCA 2026-10-10): a live channel whose stream failed to open spun forever on desktop.
 * The native bridges ignored mpv's END_FILE, and once a failed open leaves mpv idle `eof-reached`
 * is unavailable, so the snapshot never said "ended" and the startup policy never failed fast.
 * The bridges now remember the last END_FILE reason (cleared on START_FILE) and the controller
 * maps it onto the polled snapshot through [LivePlaybackStartupPolicy.endFileSnapshot].
 */
class MpvEndFileReasonTest {

    private val pending = PlayerPlaybackSnapshot(isLoading = true)

    @Test
    fun `mpv reason codes map to the names the shared policy reads`() {
        // mpv/client.h: EOF=0, STOP=2, QUIT=3, ERROR=4, REDIRECT=5.
        assertEquals("eof", MpvEndFileReason.name(0))
        assertEquals("stop", MpvEndFileReason.name(2))
        assertEquals("quit", MpvEndFileReason.name(3))
        assertEquals("error", MpvEndFileReason.name(4))
        assertEquals("redirect", MpvEndFileReason.name(5))
        assertEquals(null, MpvEndFileReason.name(MpvEndFileReason.NONE))
    }

    @Test
    fun `a failed live open ends the polled snapshot so startup fails fast`() {
        val ended = MpvEndFileReason.applyTo(pending, code = 4, isLive = true)
        assertTrue(ended.isEnded, "END_FILE error on a live channel must read as ended")
        assertFalse(ended.isLoading, "the spinner must give way")
        assertTrue(LivePlaybackStartupPolicy().sample(ended, elapsedMs = 0L), "startup must fail without waiting 20 s")
    }

    @Test
    fun `no END_FILE or a stop leaves the snapshot alone`() {
        assertEquals(pending, MpvEndFileReason.applyTo(pending, MpvEndFileReason.NONE, isLive = true))
        assertEquals(pending, MpvEndFileReason.applyTo(pending, code = 2, isLive = true))
    }

    @Test
    fun `VOD and replays keep their own end handling`() {
        // A failed VOD open must not read as "finished" (watched-marking, auto-next).
        assertEquals(pending, MpvEndFileReason.applyTo(pending, code = 4, isLive = false))
    }

    @Test
    fun `every desktop bridge reports the END_FILE reason`() {
        for (os in listOf("macos/player_bridge.mm", "linux/player_bridge.cpp", "windows/player_bridge.cpp")) {
            val bridge = sourceFile("composeApp/src/desktopMain/native/$os").readText()
            // Linux spells its JNI symbols through the NP(name) macro.
            assertTrue(
                "NativePlayerBridge_endFileReason" in bridge || "NP(endFileReason)" in bridge,
                "$os must export endFileReason",
            )
            assertTrue("MPV_EVENT_END_FILE" in bridge, "$os must observe END_FILE")
            assertTrue("MPV_EVENT_START_FILE" in bridge, "$os must clear the reason when a new file starts")
        }
    }

    private fun sourceFile(relativePath: String): Path {
        val relative = Path.of(relativePath)
        return generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .map { it.resolve(relative) }
            .firstOrNull(Files::isRegularFile)
            ?: error("Could not locate $relative from ${System.getProperty("user.dir")}")
    }
}
