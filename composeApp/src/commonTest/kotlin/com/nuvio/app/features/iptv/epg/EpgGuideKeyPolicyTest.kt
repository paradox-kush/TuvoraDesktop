package com.nuvio.app.features.iptv.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** B10/F14 read precedence: manual pick → ingest map → provider id. Twin: NuvioTV EpgGuideKeyPolicyTest. */
class EpgGuideKeyPolicyTest {

    private val keys = mapOf("itv1.uk" to "s1:itv1.uk")

    @Test
    fun aManualPickWinsWhenTheGuideCarriesIt() {
        assertEquals("s1:itv1.uk", EpgGuideKeyPolicy.resolve("ITV1.uk", keys::get, mappedKey = "bbc1.uk", providerId = "x"))
    }

    @Test
    fun aPickTheGuideDoesNotCarryFallsBackToTheMap() {
        assertEquals("bbc1.uk", EpgGuideKeyPolicy.resolve("gone.uk", keys::get, mappedKey = "bbc1.uk", providerId = "x"))
    }

    @Test
    fun beforeAnyMatchedIngestTheProviderIdIsFoldedLikeToday() {
        assertEquals("cnn.us", EpgGuideKeyPolicy.resolve(null, keys::get, mappedKey = null, providerId = " CNN.us "))
        assertNull(EpgGuideKeyPolicy.resolve(null, keys::get, mappedKey = null, providerId = ""))
    }
}
