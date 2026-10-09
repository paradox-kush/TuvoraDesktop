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

class PluginRuntimeDesktopTest {
    // A preceding player test can leave discovery paused. These exercise the engines
    // under that exact condition, independently of the UI admission gate.
    @Before fun pauseDiscovery() = PluginRuntime.setSearchPaused(true)
    @After fun restoreDiscovery() = PluginRuntime.setSearchPaused(false)

    @Test(timeout = 30_000L)
    fun `desktop runtime executes scraper code`() = runBlocking {
        val results = PluginRuntime.executePlugin(
            code = """
                module.exports.getStreams = async function(tmdbId, mediaType) {
                    return [{
                        title: "Desktop stream " + tmdbId + " " + mediaType,
                        url: "https://example.test/movie.mp4",
                        quality: "1080p",
                        provider: "Desktop Test"
                    }];
                };
            """.trimIndent(),
            tmdbId = "603",
            mediaType = "movie",
            season = null,
            episode = null,
            scraperId = "desktop-runtime-test",
            // Exercise engines independently of playback UI search admission.
            respectSearchPause = false,
        )

        assertEquals(1, results.size)
        assertEquals("Desktop stream 603 movie", results.single().title)
        assertEquals("https://example.test/movie.mp4", results.single().url)
        assertEquals("1080p", results.single().quality)
        assertEquals("Desktop Test", results.single().provider)
    }

    @Test(timeout = 30_000L)
    fun `desktop runtime handles concurrent scraper executions`() = runBlocking {
        val results = coroutineScope {
            (0 until 32).map { index ->
                async(Dispatchers.Default) {
                    PluginRuntime.executePlugin(
                        code = """
                            module.exports.getStreams = async function(tmdbId, mediaType) {
                                await Promise.resolve();
                                return [{
                                    title: "Concurrent stream " + tmdbId + " " + mediaType,
                                    url: "https://example.test/" + tmdbId + ".mp4",
                                    provider: "Desktop Stress Test"
                                }];
                            };
                        """.trimIndent(),
                        tmdbId = index.toString(),
                        mediaType = "movie",
                        season = null,
                        episode = null,
                        scraperId = "desktop-runtime-stress-$index",
                        // Exercise engines independently of playback UI search admission.
                        respectSearchPause = false,
                    )
                }
            }.awaitAll()
        }

        assertEquals(32, results.size)
        results.forEachIndexed { index, streams ->
            assertEquals(1, streams.size)
            assertEquals("https://example.test/$index.mp4", streams.single().url)
        }
    }
}
