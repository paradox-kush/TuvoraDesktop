package com.nuvio.app.features.player.desktop

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled libmpv defaults to tls-verify=no. The controller tells the native bridge, right before each create, whether
 * THAT source may be verified: yes unless the source is an IPTV panel (PlaybackTlsPolicy.PermissivePanel).
 */
class NativePlayerTlsVerifyTest {
    private fun attachAndCollect(vararg verify: Boolean): List<String> {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val created = CountDownLatch(verify.size)
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = { _, source, _, _, _, _, _, _, _ ->
                events += "create:$source"
                created.countDown()
                41L
            },
            nativeDispose = {},
            applyTlsVerify = { v -> events += "tls:$v" },
            isHostDisplayable = { true },
            resolveHostView = { NativePlayerHostTarget(viewPtr = 1L) },
            controlsPageUrl = { "about:blank" },
        )
        verify.forEachIndexed { index, v ->
            controller.attach(
                sourceUrl = "https://example.invalid/$index.mp4",
                sourceHeaders = emptyMap(),
                playWhenReady = true,
                initialPositionMs = 0L,
                decoderPriority = 0,
                nvidiaRtxSuperResolutionEnabled = false,
                tlsVerify = v,
                onError = {},
            )
            assertTrue(created.await(2, TimeUnit.SECONDS) || index < verify.lastIndex)
            controller.dispose()
        }
        SwingUtilities.invokeAndWait {}
        return events.toList()
    }

    @Test
    fun aVerifiedSourceTellsTheBridgeToVerifyBeforeItCreates() {
        assertEquals(listOf("tls:true", "create:https://example.invalid/0.mp4"), attachAndCollect(true).take(2))
    }

    @Test
    fun aPanelSourceTellsTheBridgeNotToVerifyBeforeItCreates() {
        assertEquals(listOf("tls:false", "create:https://example.invalid/0.mp4"), attachAndCollect(false).take(2))
    }
}
