package com.nuvio.app

import com.nuvio.app.core.contracts.PlaybackTrustHosts
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.servers.ServerTrust
import java.io.File
import kotlin.system.exitProcess

/**
 * Unattended evidence for the player smoke window (`-Dnuvio.desktop.smokePlayerUrl=<url>`): the real native bridge plays a URL
 * and this writes what libmpv reports to a file, so a run can be judged without watching the screen. Dev tooling only - every
 * hook is opt-in through a system property and does nothing otherwise:
 *
 *  - `nuvio.desktop.smokeReport=<file>`          append `t=.. pos=.. dur=.. playing=.. ended=.. size=.. ticks=..` once per change, and `ERROR ...`
 *  - `nuvio.desktop.smokePin=<host:port>=<SHA256>` pin a self-signed server the way the trust prompt would (device-local pin store)
 *  - `nuvio.desktop.smokePanelHost=<host>`       classify this host as an IPTV panel (PermissivePanel: libmpv may skip verification)
 *  - `nuvio.desktop.smokeSeekMs=<ms>`            seek once the duration is known (proves ranged reads)
 *  - `nuvio.desktop.smokeExitAfterMs=<ms>`       end the process, so a scripted run needs no one at the window
 */
internal object DesktopSmokeHarness {
    private val reportFile: File? = System.getProperty("nuvio.desktop.smokeReport")?.takeIf { it.isNotBlank() }?.let(::File)
    private val startedAt = System.currentTimeMillis()
    private var lastLine: String? = null
    private var seeked = false

    fun prepare() {
        System.getProperty("nuvio.desktop.smokePin")?.takeIf { it.contains('=') }?.let { spec ->
            ServerTrust.shared.pin(spec.substringBefore('='), spec.substringAfter('=').replace(":", "").uppercase())
        }
        System.getProperty("nuvio.desktop.smokePanelHost")?.takeIf { it.isNotBlank() }?.let { host ->
            PlaybackTrustHosts.registerPanelHostPredicate("smoke") { it.equals(host, ignoreCase = true) }
        }
        System.getProperty("nuvio.desktop.smokeExitAfterMs")?.toLongOrNull()?.let { ms ->
            Thread({ Thread.sleep(ms); report("EXIT"); exitProcess(0) }, "smoke-exit").apply { isDaemon = true; start() }
        }
        report("START")
    }

    fun onSnapshot(snapshot: PlayerPlaybackSnapshot, controller: PlayerEngineController?) {
        report(
            "pos=${snapshot.positionMs} dur=${snapshot.durationMs} playing=${snapshot.isPlaying} ended=${snapshot.isEnded} " +
                "loading=${snapshot.isLoading} size=${snapshot.videoWidth}x${snapshot.videoHeight} ticks=${snapshot.videoProgressTicks}",
            dedupeKey = "${snapshot.positionMs / 1000}-${snapshot.isPlaying}-${snapshot.isEnded}-${snapshot.durationMs}",
        )
        val seekTo = System.getProperty("nuvio.desktop.smokeSeekMs")?.toLongOrNull()
        if (!seeked && seekTo != null && controller != null && snapshot.durationMs > 0 && snapshot.positionMs > 1_000) {
            seeked = true
            report("SEEK to=$seekTo from=${snapshot.positionMs}")
            controller.seekTo(seekTo)
        }
    }

    fun onError(message: String?) = report("ERROR ${message.orEmpty().take(200)}")

    private fun report(line: String, dedupeKey: String = line) {
        val file = reportFile ?: return
        synchronized(this) {
            if (dedupeKey == lastLine) return
            lastLine = dedupeKey
            runCatching { file.appendText("t=${System.currentTimeMillis() - startedAt} $line\n") }
        }
    }
}
