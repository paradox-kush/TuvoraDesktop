package com.nuvio.app.features.profiles

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression for the verify_profile_pin RPC decode.
 *
 * The RPC returns a single JSON OBJECT ({"unlocked":true,"retry_after_seconds":0}), not an array.
 * supabase-kt 3.6.0's decodeSingle<T>() == decodeList<T>().first(), which decodes the body as a
 * List<T> first and therefore throws on an object — sending every verify into verifyPinLocally.
 * decodeAs<T>() decodes the object directly. This test pins both facts.
 */
class PinVerifyResultDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val objectPayload = """{"unlocked":true,"retry_after_seconds":0}"""

    @Test
    fun decodesFromJsonObject() {
        val result = json.decodeFromString<PinVerifyResult>(objectPayload)
        assertEquals(true, result.unlocked, "unlocked should decode from the RPC object body")
        assertEquals(0, result.retryAfterSeconds, "retry_after_seconds should decode from the object body")
    }

    @Test
    fun decodingObjectAsListThrows() {
        assertFailsWith<Exception> {
            json.decodeFromString<List<PinVerifyResult>>(objectPayload)
        }
    }

    @Test
    fun decodesAsListWhenBodyIsAnArray() {
        // Sanity: the List path only works on an actual array body, which the RPC never sends.
        val list = json.decodeFromString<List<PinVerifyResult>>("[$objectPayload]")
        assertEquals(1, list.size, "single-element array should decode to a one-item list")
        assertTrue(list.first().unlocked, "array element should decode with unlocked=true")
    }
}
