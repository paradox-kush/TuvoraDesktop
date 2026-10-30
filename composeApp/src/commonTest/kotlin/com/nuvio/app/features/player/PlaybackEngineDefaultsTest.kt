package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackEngineDefaultsTest {
    @Test fun upstreamVodAndForkLiveDefaultsRemainSeparate() {
        assertEquals(AndroidPlaybackEngine.ExoPlayer, PlaybackEngineDefaults.forSession(AndroidPlaybackEngine.ExoPlayer, false, null, false))
        assertEquals(AndroidPlaybackEngine.Libmpv, PlaybackEngineDefaults.forSession(AndroidPlaybackEngine.ExoPlayer, false, null, true))
    }
    @Test fun explicitSettingsAndSessionOverridesWinForLive() {
        assertEquals(AndroidPlaybackEngine.ExoPlayer, PlaybackEngineDefaults.forSession(AndroidPlaybackEngine.ExoPlayer, true, null, true))
        assertEquals(AndroidPlaybackEngine.Auto, PlaybackEngineDefaults.forSession(AndroidPlaybackEngine.ExoPlayer, false, AndroidPlaybackEngine.Auto, true))
    }
}
