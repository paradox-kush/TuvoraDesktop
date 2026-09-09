package com.nuvio.app.features.player

import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncInt
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioural test through the REAL desktop key-value store: a device's local engine/decoder/RTX
 * choice must survive both an import carrying a DIFFERENT value (a stale server blob written before
 * the exclusion) and an import that OMITS the key, while genuinely-synced preferences still apply.
 * The one JVM-testable KMP storage actual (Android needs Robolectric, iOS needs a device); the shared
 * decision it relies on ([PlayerSyncLocalKeys]) is covered by PlayerSyncLocalKeysTest on JVM + Native.
 */
class PlayerSettingsSyncLocalTest {
    companion object {
        init {
            // Redirect the desktop settings file to a throwaway dir — never touch the real config.
            // DesktopStorage reads user.home when a named store is first created (before any test body).
            System.setProperty("user.home", Files.createTempDirectory("nuvio-settings-test").toString())
        }
    }

    @BeforeTest
    fun setUp() {
        PlayerSettingsStorage.saveAndroidPlaybackEngine("libmpv")
        PlayerSettingsStorage.saveDecoderPriority(2)
        PlayerSettingsStorage.savePreferredAudioLanguage("en")
    }

    private fun payload(vararg entries: Pair<String, JsonObject>) = JsonObject(entries.toMap())

    @Test
    fun `a stale remote payload does not overwrite the local engine decoder or rtx`() {
        val remote = payload(
            "android_playback_engine" to encodeSyncString("exoplayer"),
            "decoder_priority" to encodeSyncInt(0),
            "nvidia_rtx_super_resolution_enabled" to encodeSyncBoolean(true),
            "preferred_audio_language" to encodeSyncString("fr"),
        )
        PlayerSettingsStorage.replaceFromSyncPayload(remote)
        assertEquals("libmpv", PlayerSettingsStorage.loadAndroidPlaybackEngine(), "local engine preserved")
        assertEquals(2, PlayerSettingsStorage.loadDecoderPriority(), "local decoder preserved")
        assertEquals("fr", PlayerSettingsStorage.loadPreferredAudioLanguage(), "synced preference applied")
    }

    @Test
    fun `an import that omits the engine key does not wipe the local value`() {
        PlayerSettingsStorage.replaceFromSyncPayload(payload("preferred_audio_language" to encodeSyncString("de")))
        assertEquals("libmpv", PlayerSettingsStorage.loadAndroidPlaybackEngine(), "absent key must not clear local engine")
        assertEquals("de", PlayerSettingsStorage.loadPreferredAudioLanguage())
    }

    @Test
    fun `export omits device-local keys but keeps synced ones`() {
        val exported = PlayerSettingsStorage.exportToSyncPayload()
        assertFalse(exported.containsKey("android_playback_engine"), "engine must not upload")
        assertFalse(exported.containsKey("decoder_priority"), "decoder must not upload")
        assertFalse(exported.containsKey("nvidia_rtx_super_resolution_enabled"), "rtx must not upload")
        assertTrue(exported.containsKey("preferred_audio_language"), "synced preference still exported")
    }

    @Test
    fun `two devices with different engines each keep their own`() {
        val fromDeviceA = PlayerSettingsStorage.exportToSyncPayload() // A local engine = libmpv
        PlayerSettingsStorage.saveAndroidPlaybackEngine("exoplayer") // now acting as device B
        PlayerSettingsStorage.replaceFromSyncPayload(fromDeviceA)
        assertEquals("exoplayer", PlayerSettingsStorage.loadAndroidPlaybackEngine(), "B's engine untouched by A's blob")
    }
}
