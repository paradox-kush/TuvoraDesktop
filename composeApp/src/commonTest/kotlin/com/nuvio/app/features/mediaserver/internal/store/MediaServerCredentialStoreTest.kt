package com.nuvio.app.features.mediaserver.internal.store

import com.nuvio.app.features.mediaserver.internal.FakeSecureTokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaServerCredentialStoreTest {
    @Test
    fun aCredentialRoundTripsPerServerKey() {
        val secure = FakeSecureTokenStore()
        val store = MediaServerCredentialStore(secure)
        store.save("jellyfin:m:u", StoredCredential("tok-1", "kid"))
        store.save("emby:m:u", StoredCredential("tok-2"))
        assertEquals("tok-1", store.token("jellyfin:m:u"))
        assertEquals("kid", store.credential("jellyfin:m:u")?.userName)
        assertEquals("tok-2", store.token("emby:m:u"))
        store.remove("jellyfin:m:u")
        assertNull(store.token("jellyfin:m:u"))
        assertEquals("tok-2", store.token("emby:m:u"))
    }

    @Test
    fun aBlankTokenCannotBeSavedAndAnUnreadableItemReadsAsSignedOut() {
        val secure = FakeSecureTokenStore()
        val store = MediaServerCredentialStore(secure)
        assertFailsWith<IllegalArgumentException> { store.save("k", StoredCredential("  ")) }
        secure.items["cred:k"] = "{not json"
        assertNull(store.token("k"))
        secure.items["cred:k2"] = """{"accessToken":""}"""
        assertNull(store.token("k2"))
    }

    @Test
    fun theDeviceIdIsMintedOnceAndStable() {
        val secure = FakeSecureTokenStore()
        val a = MediaServerCredentialStore(secure).deviceId()
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(a, MediaServerCredentialStore(secure).deviceId(), "survives a restart")
        assertNotEquals(a, MediaServerCredentialStore(FakeSecureTokenStore()).deviceId(), "unique per install")
        val salt = MediaServerCredentialStore(secure).installSalt()
        assertNotEquals(a, salt)
        assertEquals(salt, MediaServerCredentialStore(secure).installSalt())
    }

    @Test
    fun aFailingSecureStoreSurfacesInsteadOfPretendingToBeSignedIn() {
        val secure = FakeSecureTokenStore().apply { failWrites = true }
        assertFailsWith<IllegalStateException> { MediaServerCredentialStore(secure).save("k", StoredCredential("t")) }
        assertTrue(secure.items.isEmpty())
    }

    @Test
    fun clearAllRemovesEverythingIncludingTheDeviceId() {
        val secure = FakeSecureTokenStore()
        val store = MediaServerCredentialStore(secure)
        store.save("k", StoredCredential("t"))
        val id = store.deviceId()
        store.clearAll()
        assertFalse(secure.items.isNotEmpty())
        assertNotEquals(id, store.deviceId(), "a wiped device is a new install")
    }
}
