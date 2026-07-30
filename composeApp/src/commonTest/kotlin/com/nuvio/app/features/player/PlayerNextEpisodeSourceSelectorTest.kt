package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerNextEpisodeSourceSelectorTest {

    @Test
    fun `same provider wins over binge group and order`() {
        val other = stream(addonName = "AddonA", url = "https://a/1.mkv", bingeGroup = "group-x")
        val sameProvider = stream(addonName = "AddonB", url = "https://b/1.mkv")

        val selection = select(
            streams = listOf(other, sameProvider),
            currentProviderAddonId = "addon:AddonB",
            currentBingeGroup = "group-x",
        )

        assertEquals(sameProvider, selection?.stream)
        assertEquals(NextEpisodeSourceTier.SAME_PROVIDER, selection?.tier)
    }

    @Test
    fun `same binge group within provider refines the pick`() {
        val providerOther = stream(addonName = "AddonB", url = "https://b/720.mkv", bingeGroup = "other")
        val providerSameGroup = stream(addonName = "AddonB", url = "https://b/1080.mkv", bingeGroup = "chain")

        val selection = select(
            streams = listOf(providerOther, providerSameGroup),
            currentProviderAddonId = "addon:AddonB",
            currentBingeGroup = "chain",
        )

        assertEquals(providerSameGroup, selection?.stream)
        assertEquals(NextEpisodeSourceTier.SAME_PROVIDER, selection?.tier)
    }

    @Test
    fun `falls back to binge group on another provider when same provider is absent`() {
        val unrelated = stream(addonName = "AddonA", url = "https://a/1.mkv", bingeGroup = "nope")
        val sameChain = stream(addonName = "AddonC", url = "https://c/1.mkv", bingeGroup = "chain")

        val selection = select(
            streams = listOf(unrelated, sameChain),
            currentProviderAddonId = "addon:Gone",
            currentBingeGroup = "chain",
        )

        assertEquals(sameChain, selection?.stream)
        assertEquals(NextEpisodeSourceTier.SAME_RELEASE_GROUP, selection?.tier)
    }

    @Test
    fun `falls back to same language when provider and chain are gone`() {
        val german = stream(addonName = "AddonA", url = "https://a/1.mkv", name = "S02E01 German 1080p")
        val hindi = stream(addonName = "AddonC", url = "https://c/1.mkv", name = "S02E01 Hindi 720p WEB")

        val selection = select(
            streams = listOf(german, hindi),
            currentProviderAddonId = "addon:Gone",
            currentBingeGroup = null,
            currentLanguages = setOf("hi"),
        )

        assertEquals(hindi, selection?.stream)
        assertEquals(NextEpisodeSourceTier.SAME_LANGUAGE, selection?.tier)
    }

    @Test
    fun `uppercase scene tags count as language markers`() {
        val english = stream(addonName = "AddonA", url = "https://a/1.mkv", name = "S02E01 [ENG] 1080p")
        val tamil = stream(addonName = "AddonB", url = "https://b/1.mkv", name = "S02E01 [TAM] 1080p")

        val selection = select(
            streams = listOf(english, tamil),
            currentProviderAddonId = null,
            currentBingeGroup = null,
            currentLanguages = setOf("ta"),
        )

        assertEquals(tamil, selection?.stream)
    }

    @Test
    fun `titlecase words that collide with language codes are ignored`() {
        // "Ben" is ISO-639-2 for Bengali but here it is a name.
        val languages = PlayerNextEpisodeSourceSelector.detectStreamLanguages(
            stream(addonName = "AddonA", url = "https://a/1.mkv", name = "Ben 10 S01E02 1080p"),
        )
        assertTrue(languages.isEmpty(), "expected no languages, got $languages")
    }

    @Test
    fun `multi audio releases match any target language`() {
        val multi = stream(addonName = "AddonA", url = "https://a/1.mkv", name = "S02E01 MULTI 1080p")

        val selection = select(
            streams = listOf(multi),
            currentProviderAddonId = null,
            currentBingeGroup = null,
            currentLanguages = setOf("ko"),
        )

        assertEquals(multi, selection?.stream)
        assertEquals(NextEpisodeSourceTier.SAME_LANGUAGE, selection?.tier)
    }

    @Test
    fun `untagged release matches only when watching the original audio`() {
        val untagged = stream(addonName = "AddonA", url = "https://a/1.mkv", name = "S02E01 1080p WEB-DL")

        assertTrue(
            PlayerNextEpisodeSourceSelector.streamMatchesLanguage(
                stream = untagged,
                targets = setOf("en"),
                contentOriginalLanguage = "en",
            ),
        )
        assertFalse(
            PlayerNextEpisodeSourceSelector.streamMatchesLanguage(
                stream = untagged,
                targets = setOf("hi"),
                contentOriginalLanguage = "en",
            ),
        )
    }

    @Test
    fun `no confident tier yields null unless any-fallback is allowed`() {
        val german = stream(addonName = "AddonA", url = "https://a/1.mkv", name = "S02E01 German 1080p")

        val strict = select(
            streams = listOf(german),
            currentProviderAddonId = "addon:Gone",
            currentBingeGroup = "chain",
            currentLanguages = setOf("hi"),
        )
        assertNull(strict)

        val fallback = select(
            streams = listOf(german),
            currentProviderAddonId = "addon:Gone",
            currentBingeGroup = "chain",
            currentLanguages = setOf("hi"),
            allowAnyFallback = true,
        )
        assertEquals(german, fallback?.stream)
        assertEquals(NextEpisodeSourceTier.ANY, fallback?.tier)
    }

    @Test
    fun `confident-tiers-only skips the language tier`() {
        val hindi = stream(addonName = "AddonC", url = "https://c/1.mkv", name = "S02E01 Hindi 720p")

        val selection = select(
            streams = listOf(hindi),
            currentProviderAddonId = "addon:Gone",
            currentBingeGroup = null,
            currentLanguages = setOf("hi"),
            confidentTiersOnly = true,
        )

        assertNull(selection)
    }

    @Test
    fun `unready streams never get selected`() {
        val sameProviderUnready = stream(addonName = "AddonB", url = null, bingeGroup = "chain")
        val otherReady = stream(addonName = "AddonA", url = "https://a/1.mkv", bingeGroup = "chain")

        val selection = select(
            streams = listOf(sameProviderUnready, otherReady),
            currentProviderAddonId = "addon:AddonB",
            currentBingeGroup = "chain",
        )

        assertEquals(otherReady, selection?.stream)
        assertEquals(NextEpisodeSourceTier.SAME_RELEASE_GROUP, selection?.tier)
    }

    @Test
    fun `language text detection handles names codes and multi markers`() {
        assertEquals(setOf("hi"), languageCodesInText("Show S01E01 Hindi 1080p"))
        assertEquals(setOf("en"), languageCodesInText("Show S01E01 [ENG] WEB"))
        assertEquals(setOf(MULTI_LANGUAGE_MARKER), languageCodesInText("Show S01E01 Dual Audio"))
        assertEquals(setOf("pt"), languageCodesInText("Show Portuguese 720p"))
        assertEquals(setOf("es"), languageCodesInText("Show Castellano 720p"))
        assertEquals(emptySet(), languageCodesInText("The Mar and the Ben in May"))
        assertEquals(setOf("hi", "ta", "te"), languageCodesInText("Show [HIN+TAM+TEL] 1080p"))
    }

    private fun select(
        streams: List<StreamItem>,
        currentProviderAddonId: String?,
        currentBingeGroup: String?,
        currentLanguages: Set<String> = emptySet(),
        contentOriginalLanguage: String? = null,
        allowAnyFallback: Boolean = false,
        confidentTiersOnly: Boolean = false,
    ): NextEpisodeSourceSelection? = PlayerNextEpisodeSourceSelector.select(
        streams = streams,
        currentProviderAddonId = currentProviderAddonId,
        currentBingeGroup = currentBingeGroup,
        currentLanguages = currentLanguages,
        contentOriginalLanguage = contentOriginalLanguage,
        isReady = { stream ->
            StreamAutoPlaySelector.isStreamReadyForAutoPlay(
                stream = stream,
                debridEnabled = true,
                activeResolverProviderId = null,
            )
        },
        allowAnyFallback = allowAnyFallback,
        confidentTiersOnly = confidentTiersOnly,
    )

    private fun stream(
        addonName: String,
        url: String?,
        name: String? = null,
        bingeGroup: String? = null,
    ): StreamItem = StreamItem(
        name = name,
        url = url,
        addonName = addonName,
        addonId = "addon:$addonName",
        behaviorHints = StreamBehaviorHints(bingeGroup = bingeGroup),
    )
}
