package com.nuvio.app.features.plugins

import com.nuvio.app.features.plugins.runtime.PluginRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * quickjs-kt reference-counts JNI globals shared by every engine. With engines created and closed
 * concurrently, a close that dropped the count to zero raced another engine's create: evaluate()
 * then NPE'd ("this.closed is null") or lost the result callback and hung to the 60 s plugin
 * timeout. It showed up on the FIRST burst of a cold JVM (round 0), so this test repeats the burst
 * rather than relying on one — a single burst passed whenever an earlier test had warmed the engine.
 */
class PluginRuntimeConcurrencyTest {
    // A preceding player test can leave discovery paused. These exercise the engines
    // under that exact condition, independently of the UI admission gate.
    @Before fun pauseDiscovery() = PluginRuntime.setSearchPaused(true)
    @After fun restoreDiscovery() = PluginRuntime.setSearchPaused(false)

    @Test(timeout = 30_000L)
    fun `repeated concurrent bursts neither hang nor crash`() = runBlocking {
        repeat(ROUNDS) { round ->
            val results = coroutineScope {
                (0 until BURST).map { index ->
                    async(Dispatchers.Default) {
                        PluginRuntime.executePlugin(
                            code = """
                                module.exports.getStreams = async function(tmdbId, mediaType) {
                                    await Promise.resolve();
                                    return [{ title: "s" + tmdbId, url: "https://example.test/" + tmdbId + ".mp4", provider: "p" }];
                                };
                            """.trimIndent(),
                            tmdbId = index.toString(),
                            mediaType = "movie",
                            season = null,
                            episode = null,
                            scraperId = "concurrency-$round-$index",
                            // Exercise engines independently of playback UI search admission.
                            respectSearchPause = false,
                        )
                    }
                }.awaitAll()
            }
            results.forEachIndexed { index, streams ->
                assertEquals("https://example.test/$index.mp4", streams.single().url, "round $round index $index")
            }
        }
    }

    private companion object {
        const val ROUNDS = 20
        const val BURST = 32
    }
}
