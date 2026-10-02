package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [HttpProviderSetupApi] on fake transports — no network. The code never leaves except as the contract says. */
class ProviderSetupApiTest {

    private class FakePreview(var response: () -> RawHttpResponse) : ProviderPreviewTransport {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun get(url: String, headers: Map<String, String>): RawHttpResponse {
            requests += url to headers
            return response()
        }
    }

    private class FakeRpc(var answer: (String, JsonObject) -> JsonElement) : ProviderRpcTransport {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun call(function: String, params: JsonObject): JsonElement {
            calls += function to params
            return answer(function, params)
        }
    }

    private fun http(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        RawHttpResponse(status = status, statusText = "", url = "", body = body, headers = headers)

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private val previewBody = """
        {"preview":{"provider_name":"Acme TV","support":{"telegram":"acme_tv","email":"help@acme.example.com"},
         "package_name":"Gold","playlists":[{"name":"Acme Live","source_type":"xtream"},{"name":"Acme M3U","source_type":"m3u_url"}],
         "addons":["Cinemeta","https://leak.example.com/key=SECRET/manifest.json"],"status":"open","expires_at":"2026-11-01T00:00:00Z",
         "credentials":{"username":"u","password":"p"},"base_url":"http://secret.example.com"}}
    """.trimIndent()

    private fun api(
        preview: FakePreview = FakePreview { http(200, previewBody) },
        rpc: FakeRpc = FakeRpc { _, _ -> json("{}") },
        token: String? = null,
    ) = HttpProviderSetupApi(preview, rpc, webBaseUrl = { "https://tuvora.co" }, bearerToken = { token })

    @Test
    fun `a malformed code never reaches the network`() = runBlocking {
        val fake = FakePreview { http(200, previewBody) }
        val api = api(fake)
        assertEquals(SetupCodeOutcome.Problem(SetupCodeProblem.EMPTY), api.preview(""))
        assertEquals(SetupCodeOutcome.Problem(SetupCodeProblem.BAD_CHARACTERS), api.preview("ABCD-0000-0000"))
        assertEquals(SetupCodeOutcome.Problem(SetupCodeProblem.WRONG_LENGTH), api.preview("ABCD-EFGH"))
        assertEquals(0, fake.requests.size, "no request, so no rate-limit strike")
    }

    @Test
    fun `a valid code asks the web route with the formatted code and no auth when signed out`() = runBlocking {
        val fake = FakePreview { http(200, previewBody) }
        api(fake).preview("abcdefghjkmn")
        assertEquals("https://tuvora.co/api/s/preview?code=TUV-ABCD-EFGH-JKMN", fake.requests.single().first)
        assertFalse(fake.requests.single().second.containsKey("Authorization"))
    }

    @Test
    fun `a signed-in caller sends its token for the per-account bucket`() = runBlocking {
        val fake = FakePreview { http(200, previewBody) }
        api(fake, token = "tok123").preview("abcdefghjkmn")
        assertEquals("Bearer tok123", fake.requests.single().second["Authorization"])
    }

    @Test
    fun `a 200 preview is parsed from the allow-list only`() = runBlocking {
        val outcome = api().preview("abcdefghjkmn") as SetupCodeOutcome.Ready
        val p = outcome.preview
        assertEquals("Acme TV", p.providerName)
        assertEquals("Gold", p.packageName)
        assertEquals(listOf(SetupPreviewPlaylist("Acme Live", "xtream"), SetupPreviewPlaylist("Acme M3U", "m3u_url")), p.playlists)
        assertEquals(listOf("Cinemeta"), p.addons, "an add-on shown by name; a URL-shaped entry is dropped")
        assertEquals(listOf("https://t.me/acme_tv", "mailto:help@acme.example.com"), p.support.links().map { it.url })
        assertEquals("2026-11-01T00:00:00Z", p.expiresAt)
        assertFalse(p.toString().contains("secret.example.com"), "no address survives into the model")
        assertFalse(p.toString().contains("password"), "no credential survives into the model")
    }

    @Test
    fun `an unreadable 200 is neutral`() = runBlocking {
        assertEquals(SetupCodeOutcome.Unusable, api(FakePreview { http(200, "<html>oops</html>") }).preview("abcdefghjkmn"))
        assertEquals(SetupCodeOutcome.Unusable, api(FakePreview { http(200, """{"preview":{"playlists":[]}}""") }).preview("abcdefghjkmn"))
    }

    @Test
    fun `http errors map through the table and 429 carries Retry-After`() = runBlocking {
        suspend fun outcome(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
            api(FakePreview { http(status, body, headers) }).preview("abcdefghjkmn")
        assertEquals(SetupCodeOutcome.Unusable, outcome(404, """{"error":"not_found","code":"not_found"}"""))
        assertEquals(SetupCodeOutcome.Unusable, outcome(409, """{"error":"used","code":"used"}"""))
        assertEquals(SetupCodeOutcome.Expired(ProviderSupport.NONE), outcome(410, """{"error":"expired","code":"expired"}"""))
        assertEquals(SetupCodeOutcome.RateLimited(42), outcome(429, """{"error":"x","code":"rate_limited"}""", mapOf("Retry-After" to "42")))
        assertEquals(SetupCodeOutcome.RateLimited(7), outcome(429, "", mapOf("retry-after" to "7")))
        assertEquals(SetupCodeOutcome.Unusable, outcome(404, """{"error":"Not found"}"""), "feature off")
        assertEquals(SetupCodeOutcome.Network, outcome(502, "<html>Bad gateway</html>"))
    }

    @Test
    fun `a transport failure is a network outcome and never throws`() = runBlocking {
        val failing = FakePreview { throw RuntimeException("Unable to resolve host tuvora.co") }
        assertEquals(SetupCodeOutcome.Network, api(failing).preview("abcdefghjkmn"))
    }

    @Test
    fun `redeem sends the normalized 12 characters and the profile index`() = runBlocking {
        val rpc = FakeRpc { _, _ ->
            json("""{"ok":true,"status":"redeemed","profile_index":2,"added":1,"updated":1,"unchanged":0,
                "playlists":[{"playlist_key":"http://a|u","name":"A","action":"added"},{"playlist_key":"b","name":"B","action":"updated"}],
                "added_addons":1,"skipped_addons":0}""")
        }
        val result = api(rpc = rpc).redeem("TUV-ABCD-EFGH-JKMN", 2)
        val call = rpc.calls.single()
        assertEquals("redeem_setup", call.first)
        assertEquals("ABCDEFGHJKMN", call.second["p_code"]?.jsonPrimitive?.content)
        assertEquals("2", call.second["p_profile_index"]?.jsonPrimitive?.content)
        val summary = (result as RedeemResult.Redeemed).summary
        assertEquals(1, summary.added)
        assertEquals(1, summary.updated)
        assertEquals(listOf("http://a|u", "b"), summary.playlists.map { it.playlistKey })
        assertEquals(1, summary.addedAddons)
        assertFalse(summary.alreadyRedeemed)
    }

    @Test
    fun `services the redeem skipped are counted with their reason`() = runBlocking {
        val rpc = FakeRpc { _, _ ->
            json("""{"ok":true,"status":"redeemed","profile_index":1,"added":0,"updated":0,"unchanged":0,
                "playlists":[{"playlist_key":null,"name":"A","action":"skipped","reason":"invalid_url"},
                             {"playlist_key":null,"name":"B","action":"skipped","reason":"missing_login"},
                             {"playlist_key":null,"name":"C","action":"skipped"}],
                "added_addons":0,"skipped_addons":0}""")
        }
        val summary = (api(rpc = rpc).redeem("abcdefghjkmn", 1) as RedeemResult.Redeemed).summary
        assertEquals(listOf("invalid_url", "missing_login", "missing_login"), summary.skippedReasons)
        assertEquals(3, summary.skipped)
        assertEquals(emptyList(), summary.playlists, "a skipped service has no playlist key")
    }

    @Test
    fun `redeeming a code this account already used is not an error`() = runBlocking {
        val rpc = FakeRpc { _, _ -> json("""{"ok":true,"status":"already_redeemed","profile_index":1,"added":0,"updated":0,"unchanged":0,"playlists":[],"added_addons":0,"skipped_addons":0}""") }
        val result = api(rpc = rpc).redeem("abcdefghjkmn", 1) as RedeemResult.Redeemed
        assertTrue(result.summary.alreadyRedeemed)
    }

    @Test
    fun `redeem refusals map through the shared outcome table`() = runBlocking {
        suspend fun refused(answer: () -> JsonElement) =
            (api(rpc = FakeRpc { _, _ -> answer() }).redeem("abcdefghjkmn", 1) as RedeemResult.Refused).outcome
        assertEquals(SetupCodeOutcome.Unusable, refused { json("""{"ok":false,"error":"already_used"}""") })
        assertEquals(SetupCodeOutcome.Expired(ProviderSupport.NONE), refused { json("""{"ok":false,"error":"expired"}""") })
        assertEquals(SetupCodeOutcome.ProfileGone, refused { json("""{"ok":false,"error":"profile_not_found"}""") })
        assertEquals(SetupCodeOutcome.RateLimited(null), refused { json("""{"ok":false,"error":"rate_limited"}""") })
        assertEquals(SetupCodeOutcome.NeedsSignIn, refused { throw ProviderRpcException("anonymous_not_allowed") })
        assertEquals(SetupCodeOutcome.NeedsSignIn, refused { throw ProviderRpcException("not_authenticated") })
        assertEquals(SetupCodeOutcome.Network, refused { throw RuntimeException("timeout") })
    }

    @Test
    fun `a malformed code is refused on the device before redeem`() = runBlocking {
        val rpc = FakeRpc { _, _ -> json("{}") }
        val result = api(rpc = rpc).redeem("ABCD", 1) as RedeemResult.Refused
        assertEquals(SetupCodeOutcome.Problem(SetupCodeProblem.WRONG_LENGTH), result.outcome)
        assertEquals(0, rpc.calls.size)
    }

    @Test
    fun `managed playlists decode leniently and skip bad rows`() = runBlocking {
        val rpc = FakeRpc { _, _ ->
            json("""[{"playlist_key":"k1","provider_name":"Acme","service_name":"Live","support":{"telegram":"acme_tv","whatsapp":null},"service_updated_at":"2026-10-01T10:00:00Z"},
                {"provider_name":"no key"},{"playlist_key":"k2","provider_name":"Beta","support":"garbage"}]""")
        }
        val list = api(rpc = rpc).managedPlaylists(3)
        assertEquals("get_managed_playlists", rpc.calls.single().first)
        assertEquals("3", rpc.calls.single().second["p_profile_id"]?.jsonPrimitive?.content)
        assertEquals(listOf("k1", "k2"), list.map { it.playlistKey })
        assertEquals("2026-10-01T10:00:00Z", list[0].serviceUpdatedAt)
        assertNull(list[1].serviceUpdatedAt, "absent from an older server: no 'updated <date>'")
        assertEquals(ProviderSupport.NONE, list[1].support)
    }

    @Test
    fun `detach reports whether anything was detached`() = runBlocking {
        val rpc = FakeRpc { _, _ -> json("""{"detached":true}""") }
        assertTrue(api(rpc = rpc).detach(2, "k1"))
        assertEquals("detach_managed_playlist", rpc.calls.single().first)
        assertEquals("k1", rpc.calls.single().second["p_playlist_key"]?.jsonPrimitive?.content)
        assertFalse(api(rpc = FakeRpc { _, _ -> json("""{"detached":false}""") }).detach(2, "k1"))
    }

    @Test
    fun `the preview times out after 15 seconds as a network outcome`() = runTest {
        val never = object : ProviderPreviewTransport {
            override suspend fun get(url: String, headers: Map<String, String>): RawHttpResponse {
                delay(10 * 60_000L)
                return http(200, previewBody)
            }
        }
        val outcome = HttpProviderSetupApi(never, FakeRpc { _, _ -> json("{}") }, { "https://tuvora.co" }, { null }).preview("abcdefghjkmn")
        assertEquals(SetupCodeOutcome.Network, outcome)
        assertEquals(15_000L, testScheduler.currentTime, "gave up at the 15 s limit, not later")
    }

    // ---- code review M3: a transient server failure on redeem is a network problem, never a dead code ---

    @Test
    fun `a gateway failure on redeem reads as a network problem`() = runBlocking {
        suspend fun refused(e: Throwable) =
            (api(rpc = FakeRpc { _, _ -> throw e }).redeem("abcdefghjkmn", 1) as RedeemResult.Refused).outcome
        assertEquals(SetupCodeOutcome.Network, refused(ProviderRpcErrors.fromRest(503, "<html>Service Unavailable</html>")))
        assertEquals(SetupCodeOutcome.Network, refused(ProviderRpcErrors.fromRest(502, "")))
        assertEquals(SetupCodeOutcome.Network, refused(ProviderRpcErrors.fromRest(504, "upstream request timeout")))
        assertEquals(SetupCodeOutcome.Network, refused(ProviderRpcErrors.fromRest(408, "")))
        assertEquals(SetupCodeOutcome.RateLimited(null), refused(ProviderRpcErrors.fromRest(429, "")))
        assertEquals(SetupCodeOutcome.NeedsSignIn, refused(ProviderRpcErrors.fromRest(401, "JWT expired")))
    }

    @Test
    fun `a raised code is still the verdict even on a 5xx status`() = runBlocking {
        val e = ProviderRpcErrors.fromRest(500, "expired")
        assertEquals("expired", e.errorCode)
        assertFalse(e.transient)
    }

    @Test
    fun `a stable raised code is read whole and not by substring`() {
        assertEquals("expired", ProviderRpcErrors.fromRest(400, "expired").errorCode)
        assertEquals("profile_not_found", ProviderRpcErrors.fromRest(400, "profile_not_found\nDetails: none").errorCode)
        assertNull(ProviderRpcErrors.fromRest(400, "connection refused by upstream").errorCode, "'used' inside 'refused' is not the code 'used'")
        assertNull(ProviderRpcErrors.fromRest(400, "JWT expired at 12:00").errorCode, "an auth failure sentence is not the code 'expired'")
    }

    // ---- (a) store flavours: p_skip_addons ------------------------------------------------------------

    @Test
    fun `skip addons is sent only when asked and the default call is unchanged`() = runBlocking {
        val rpc = FakeRpc { _, _ -> json("""{"ok":true,"status":"redeemed","profile_index":1,"added":1,"updated":0,"unchanged":0,"playlists":[],"added_addons":0,"skipped_addons":0}""") }
        api(rpc = rpc).redeem("abcdefghjkmn", 1)
        assertFalse(rpc.calls.last().second.containsKey("p_skip_addons"), "a backend without the parameter keeps working")
        api(rpc = rpc).redeem("abcdefghjkmn", 1, skipAddons = true)
        assertEquals("true", rpc.calls.last().second["p_skip_addons"]?.jsonPrimitive?.content)
        assertEquals("ABCDEFGHJKMN", rpc.calls.last().second["p_code"]?.jsonPrimitive?.content)
    }

    // ---- security L6 / L2 / L1: what the preview reader accepts --------------------------------------

    @Test
    fun `a redirect from the preview route is not followed and means unusable`() = runBlocking {
        assertEquals(SetupCodeOutcome.Unusable, api(FakePreview { http(302, "", mapOf("Location" to "https://evil.example.com/s")) }).preview("abcdefghjkmn"))
        assertEquals(SetupCodeOutcome.Unusable, api(FakePreview { http(301, "{\"preview\":{\"provider_name\":\"Acme\"}}") }).preview("abcdefghjkmn"))
    }

    @Test
    fun `an oversized preview body is refused`() = runBlocking {
        val huge = "{\"preview\":{\"provider_name\":\"Acme\",\"playlists\":[]},\"pad\":\"" + "x".repeat(70_000) + "\"}"
        assertEquals(SetupCodeOutcome.Unusable, api(FakePreview { http(200, huge) }).preview("abcdefghjkmn"))
    }

    @Test
    fun `a preview with a flood of rows keeps only the first twenty of each`() = runBlocking {
        val playlists = (1..60).joinToString(",") { """{"name":"P$it","source_type":"xtream"}""" }
        val addons = (1..60).joinToString(",") { "\"Addon $it\"" }
        val body = """{"preview":{"provider_name":"Acme","playlists":[$playlists],"addons":[$addons]}}"""
        val p = (api(FakePreview { http(200, body) }).preview("abcdefghjkmn") as SetupCodeOutcome.Ready).preview
        assertEquals(20, p.playlists.size)
        assertEquals("P1", p.playlists.first().name)
        assertEquals(20, p.addons.size)
    }

    @Test
    fun `names are capped and stripped of bidi and format characters`() = runBlocking {
        val body = """{"preview":{"provider_name":"Acme\u202E TV\u200B","package_name":"\u2066Gold\u2069",
            "playlists":[{"name":"\u200B\u202E","source_type":"xtream"},{"name":"Live\u202Egnp.exe","source_type":"xtream"}],
            "addons":["\u202ECinemeta","\u200B"]}}"""
        val p = (api(FakePreview { http(200, body) }).preview("abcdefghjkmn") as SetupCodeOutcome.Ready).preview
        assertEquals("Acme TV", p.providerName)
        assertEquals("Gold", p.packageName)
        assertEquals(listOf("Playlist", "Livegnp.exe"), p.playlists.map { it.name }, "a name that is only invisible characters falls back")
        assertEquals(listOf("Cinemeta"), p.addons)
        for (c in listOf('\u202E', '\u200B', '\u2066', '\u2069')) assertFalse(p.toString().contains(c))
    }

    @Test
    fun `provider and service names from the managed list are stripped the same way`() = runBlocking {
        val rpc = FakeRpc { _, _ -> json("""[{"playlist_key":"k1","provider_name":"\u202EAcme\u200B","service_name":"Live\u2067"}]""") }
        val info = api(rpc = rpc).managedPlaylists(1).single()
        assertEquals("Acme", info.providerName)
        assertEquals("Live", info.serviceName)
    }

    // ---- code review M12: the REAL message shapes (captured live: supabase-kt 3.6.0 vs PostgREST, 2026-10-02) ----

    private val tail = "\nURL: http://12...:58321/rest/v1/rpc/redeem_setup\nHeaders: {Authorization=[Bearer ey... (len=910)], Content-Profile=[public], Accept=[application/json], Prefer=[], apikey=[sb... (len=46)], X-Client-Info=[supabase-kt/3.6.0], X-Supabase-Client-Platform=[Android]}\nHttp Method: POST"

    @Test
    fun `a raised refusal is read from the first line of the real message`() {
        val raised = "anonymous_not_allowed\nCode: P0001\nHint: anonymous_not_allowed\nDetails: null$tail"
        assertEquals("anonymous_not_allowed", ProviderRpcErrors.fromRest(400, raised).errorCode)
        assertEquals("not_authenticated", ProviderRpcErrors.fromRest(400, "not_authenticated\nCode: P0001\nHint: not_authenticated\nDetails: null$tail").errorCode)
    }

    @Test
    fun `real session failures need sign-in and never read as a code`() {
        val anon = "permission denied for function redeem_setup\nCode: 42501\nHint: null\nDetails: null$tail"
        assertEquals("not_authenticated", ProviderRpcErrors.fromRest(401, anon).errorCode)
        val badJwt = "No suitable key or wrong key type\nCode: PGRST301\nHint: null\nDetails: \"None of the keys was able to decode the JWT\"$tail"
        assertEquals("not_authenticated", ProviderRpcErrors.fromRest(401, badJwt).errorCode)
        // A one-word PostgREST message is not a raised code unless the server says P0001.
        assertEquals("not_authenticated", ProviderRpcErrors.fromRest(403, "Forbidden\nCode: 42501\nHint: null\nDetails: null$tail").errorCode)
    }

    @Test
    fun `a function the backend lacks and a gateway failure are transient`() {
        val missing = "Could not find the function public.redeem_setup(p_code, p_profile_index, p_skip_addons) in the schema cache\nCode: PGRST202\nHint: Perhaps you meant to call the function public.redeem_setup(p_code, p_profile_index)\nDetails: \"Searched for the function public.redeem_setup with parameters p_code, p_profile_index, p_skip_addons or with a single unnamed json/jsonb parameter, but no matches were found in the schema cache.\"$tail"
        val e = ProviderRpcErrors.fromRest(404, missing)
        assertNull(e.errorCode)
        assertTrue(e.transient, "a deployment gap is a retry, never 'this code can't be used'")
        for (status in listOf(502, 503, 504)) {
            val gateway = ProviderRpcErrors.fromRest(status, "Unknown error\nCode: null\nHint: null\nDetails: null$tail")
            assertNull(gateway.errorCode)
            assertTrue(gateway.transient, "HTTP $status")
        }
    }

    @Test
    fun `a body refusal on HTTP 200 maps through the table`() = runBlocking {
        suspend fun refused(error: String) =
            (api(rpc = FakeRpc { _, _ -> json("""{"ok": false, "error": "$error"}""") }).redeem("abcdefghjkmn", 1) as RedeemResult.Refused).outcome
        assertEquals(SetupCodeOutcome.Expired(ProviderSupport.NONE), refused("expired"))
        assertEquals(SetupCodeOutcome.Unusable, refused("not_found"))
        assertEquals(SetupCodeOutcome.ProfileGone, refused("profile_not_found"))
    }
}
