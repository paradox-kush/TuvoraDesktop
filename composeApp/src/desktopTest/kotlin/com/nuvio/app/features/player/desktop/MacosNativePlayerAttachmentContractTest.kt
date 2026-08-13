package com.nuvio.app.features.player.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Native AppKit views are outside Compose's semantics/screenshot tree, so a normal Compose UI test
 * cannot detect a video view covering the guide. This architecture guard pins the faulty boundary
 * until the bridge has a dedicated macOS host integration test: resolving a player Canvas must not
 * walk back up to the top-level platform window and return its whole content view.
 */
class MacosNativePlayerAttachmentContractTest {

    @Test
    fun `macOS player resolves the requested Canvas rather than the whole window content view`() {
        val resolver = sourceFile(
            "composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/MacosAwtViewResolver.kt",
        ).readText()
        val bridge = sourceFile(
            "composeApp/src/desktopMain/native/macos/player_bridge.mm",
        ).readText()

        assertTrue("resolveNativePlayerHost(component" in resolver)
        assertTrue("needsBoundedChild = true" in resolver)
        assertTrue(
            "_hostView = [[NSView alloc] initWithFrame:playerFrame]" in bridge,
            "macOS must insert a player-sized child instead of adding video directly to the whole window view",
        )
        assertTrue("NativePlayerBridge_updateHostBounds" in bridge)
    }

    private fun sourceFile(relativePath: String): Path {
        val relative = Path.of(relativePath)
        val candidates = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .map { it.resolve(relative) }
            .toList()
        return candidates.firstOrNull(Files::isRegularFile)
            ?: error("Could not locate $relative from ${System.getProperty("user.dir")}")
    }
}
