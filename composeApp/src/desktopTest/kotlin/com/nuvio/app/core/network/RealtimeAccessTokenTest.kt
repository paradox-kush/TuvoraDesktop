package com.nuvio.app.core.network

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * E2E 2026-10-16 asked whether Realtime is handed the publishable key instead of the user's JWT. It is not:
 * the join token is the session JWT (proven live on the local stack: member JWT -> phx_reply ok on the private
 * watch-party channel). This pins it, with the app's own provider and a new-style non-JWT API key.
 */
class RealtimeAccessTokenTest {
    private val apiKey = "sb_publishable_testkey"

    private fun client() = createSupabaseClient("https://example.test", apiKey) {
        install(Auth) {
            sessionManager = MemorySessionManager()
            codeVerifierCache = MemoryCodeVerifierCache()
            alwaysAutoRefresh = false
            autoLoadFromStorage = false
        }
        install(Realtime) { accessToken = realtimeAccessToken }
    }

    @Test
    fun `a signed-in user's channels authorise with the session JWT, not the API key`() = runBlocking {
        val client = client()
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEifQ.sig"
        client.auth.importSession(UserSession(accessToken = jwt, refreshToken = "r", expiresIn = 3600, tokenType = "bearer", user = null), autoRefresh = false)
        assertEquals(jwt, client.realtime.config.accessToken(client))
    }

    @Test
    fun `signed out the provider gives nothing and the library falls back to the API key as anon`() = runBlocking {
        val client = client()
        assertNull(client.realtime.config.accessToken(client))
    }
}
