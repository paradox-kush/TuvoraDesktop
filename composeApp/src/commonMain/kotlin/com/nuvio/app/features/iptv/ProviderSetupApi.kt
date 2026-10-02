package com.nuvio.app.features.iptv

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.build.AppBuildConfig
import com.nuvio.app.core.network.SupabaseConfig
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.SyncBackendDefaults
import com.nuvio.app.core.network.SyncBackendRepository
import com.nuvio.app.core.network.hasSameConnectionIdentity
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One playlist a redeem added or updated. */
data class RedeemedPlaylist(val playlistKey: String, val name: String, val action: String)

/** What `redeem_setup` did. [alreadyRedeemed]: this account used the code before (idempotent, not an error). */
data class RedeemSummary(
    val profileIndex: Int?,
    val added: Int,
    val updated: Int,
    val unchanged: Int,
    val playlists: List<RedeemedPlaylist>,
    val addedAddons: Int,
    val skippedAddons: Int,
    val alreadyRedeemed: Boolean = false,
    /** Services the setup could not install: `missing_login` (the provider has not filled the login yet) or `invalid_url`. */
    val skippedReasons: List<String> = emptyList(),
) {
    val skipped: Int get() = skippedReasons.size
}

sealed interface RedeemResult {
    data class Redeemed(val summary: RedeemSummary) : RedeemResult
    /** Anything but success, already mapped to the shared outcome ([SetupCodeOutcome.message]). */
    data class Refused(val outcome: SetupCodeOutcome) : RedeemResult
}

/**
 * The client side of provider setup codes, behind one interface so the UI and its tests use fakes.
 * No method logs, persists or reports the code.
 */
interface ProviderSetupApi {
    /** Preview what [code] (already [SetupCode.parse]d) would add. Never throws: every failure is an outcome. */
    suspend fun preview(code: String): SetupCodeOutcome

    /** Redeem [code] into [profileIndex]. Never throws. The caller then forces ONE playlist pull. */
    suspend fun redeem(code: String, profileIndex: Int): RedeemResult

    /** `get_managed_playlists` for the profile. Throws on failure (callers keep their cache). */
    suspend fun managedPlaylists(profileId: Int): List<ManagedInfo>

    /** `detach_managed_playlist`. True when it was detached, false when it was not managed (already detached). */
    suspend fun detach(profileId: Int, playlistKey: String): Boolean
}

/** An RPC refusal carrying the server's stable error code (the raised P0001 message), or null when unknown. */
internal class ProviderRpcException(val errorCode: String?, cause: Throwable? = null) : RuntimeException(errorCode, cause)

/** The two seams the HTTP/RPC implementation runs on; tests replace them. */
internal interface ProviderPreviewTransport {
    suspend fun get(url: String, headers: Map<String, String>): RawHttpResponse
}

internal interface ProviderRpcTransport {
    /** Calls [function]; throws [ProviderRpcException] for a server refusal, anything else for transport failure. */
    suspend fun call(function: String, params: JsonObject): JsonElement
}

/** Single constants for where the preview route lives. Release builds always use tuvora.co. */
internal object ProviderSetupConfig {
    const val PRODUCTION_WEB_BASE = "https://tuvora.co"
    const val PREVIEW_TIMEOUT_MS = 15_000L

    /** The setup-code web host; a debug build may be pointed at a local web server (`PROVIDER_WEB_URL`). */
    val webBaseUrl: String
        get() = SupabaseConfig.PROVIDER_WEB_URL.trim().trimEnd('/')
            .takeIf { AppBuildConfig.IS_DEBUG_BUILD && it.isNotEmpty() }
            ?: PRODUCTION_WEB_BASE

    fun previewUrl(base: String, code: String): String = "$base/api/s/preview?code=${SetupCode.format(code)}"
}

internal object PlatformProviderPreviewTransport : ProviderPreviewTransport {
    override suspend fun get(url: String, headers: Map<String, String>): RawHttpResponse =
        httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
}

internal object SupabaseProviderRpcTransport : ProviderRpcTransport {
    override suspend fun call(function: String, params: JsonObject): JsonElement = try {
        SupabaseProvider.client.postgrest.rpc(function, params).decodeAs<JsonElement>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        // A raised P0001 carries the code as its message; a JWT/401 means the session is not usable.
        val first = e.message.orEmpty().trim().lineSequence().firstOrNull().orEmpty().trim().lowercase()
        val code = first.takeIf { it.isNotEmpty() && it.all { c -> c.isLetter() || c == '_' } }
            ?: if (e.statusCode == 401) "not_authenticated" else null
        throw ProviderRpcException(code, e)
    }
}

/**
 * The production [ProviderSetupApi]: the preview is an HTTPS GET on the web route (`preview_setup` itself
 * is service-role only), redeem / managed / detach are the Supabase RPCs. [bearerToken] is the signed-in
 * access token for the per-account rate-limit bucket (null when signed out, or when the sync backend is
 * not the hosted one, so a token for another backend is never sent to tuvora.co).
 */
internal class HttpProviderSetupApi(
    private val preview: ProviderPreviewTransport = PlatformProviderPreviewTransport,
    private val rpc: ProviderRpcTransport = SupabaseProviderRpcTransport,
    private val webBaseUrl: () -> String = { ProviderSetupConfig.webBaseUrl },
    private val bearerToken: suspend () -> String? = ::hostedAccessTokenOrNull,
) : ProviderSetupApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun preview(code: String): SetupCodeOutcome {
        // A malformed code never reaches the network (and never costs a rate-limit strike).
        when (val parsed = SetupCode.normalize(code)) {
            is SetupCodeParse.Invalid -> return SetupCodeOutcome.fromProblem(parsed.problem)
            is SetupCodeParse.Valid -> Unit
        }
        return try {
            val headers = buildMap {
                put("Accept", "application/json")
                bearerToken()?.let { put("Authorization", "Bearer $it") }
            }
            val response = withTimeout(ProviderSetupConfig.PREVIEW_TIMEOUT_MS) {
                preview.get(ProviderSetupConfig.previewUrl(webBaseUrl(), code), headers)
            }
            interpretPreview(response)
        } catch (e: TimeoutCancellationException) {
            SetupCodeOutcome.Network
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            SetupCodeOutcome.Network
        }
    }

    internal fun interpretPreview(response: RawHttpResponse): SetupCodeOutcome {
        if (response.status in 200..299) {
            val parsed = ProviderSetupCodec.preview(response.body) ?: return SetupCodeOutcome.Unusable
            return SetupCodeOutcome.Ready(parsed)
        }
        val body = runCatching { json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
        val errorCode = (body?.get("code") as? JsonPrimitive)?.contentOrNull
        val retryAfter = response.headers.entries
            .firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value?.trim()?.toIntOrNull()
        return SetupCodeOutcome.fromPreviewHttp(response.status, errorCode, retryAfter)
    }

    override suspend fun redeem(code: String, profileIndex: Int): RedeemResult {
        val normalized = when (val parsed = SetupCode.normalize(code)) {
            is SetupCodeParse.Invalid -> return RedeemResult.Refused(SetupCodeOutcome.fromProblem(parsed.problem))
            is SetupCodeParse.Valid -> parsed.code
        }
        return try {
            // Same normalized form the web claim page sends: the 12 characters, no prefix or dashes.
            val result = rpc.call("redeem_setup", buildJsonObject {
                put("p_code", normalized)
                put("p_profile_index", profileIndex)
            })
            interpretRedeem(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderRpcException) {
            RedeemResult.Refused(SetupCodeOutcome.fromErrorCode(e.errorCode))
        } catch (e: Throwable) {
            RedeemResult.Refused(SetupCodeOutcome.Network)
        }
    }

    internal fun interpretRedeem(result: JsonElement): RedeemResult {
        val o = result as? JsonObject ?: return RedeemResult.Refused(SetupCodeOutcome.Unusable)
        if ((o["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            return RedeemResult.Refused(SetupCodeOutcome.fromErrorCode((o["error"] as? JsonPrimitive)?.contentOrNull))
        }
        fun int(key: String) = (o[key] as? JsonPrimitive)?.intOrNull ?: 0
        val rows = (o["playlists"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val skippedReasons = rows
            .filter { (it["action"] as? JsonPrimitive)?.contentOrNull == "skipped" }
            .map { (it["reason"] as? JsonPrimitive)?.contentOrNull ?: "missing_login" }
        val playlists = (o["playlists"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            val key = (p["playlist_key"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            RedeemedPlaylist(
                playlistKey = key,
                name = (p["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                action = (p["action"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
        return RedeemResult.Redeemed(
            RedeemSummary(
                profileIndex = (o["profile_index"] as? JsonPrimitive)?.intOrNull,
                added = int("added"), updated = int("updated"), unchanged = int("unchanged"),
                playlists = playlists,
                addedAddons = int("added_addons"), skippedAddons = int("skipped_addons"),
                alreadyRedeemed = (o["status"] as? JsonPrimitive)?.contentOrNull == "already_redeemed",
                skippedReasons = skippedReasons,
            ),
        )
    }

    override suspend fun managedPlaylists(profileId: Int): List<ManagedInfo> =
        ProviderSetupCodec.managedPlaylists(rpc.call("get_managed_playlists", buildJsonObject { put("p_profile_id", profileId) }))

    override suspend fun detach(profileId: Int, playlistKey: String): Boolean {
        val result = rpc.call("detach_managed_playlist", buildJsonObject {
            put("p_profile_id", profileId)
            put("p_playlist_key", playlistKey)
        })
        return ((result as? JsonObject)?.get("detached") as? JsonPrimitive)?.booleanOrNull == true
    }
}

/** The signed-in (non-anonymous) access token, only while the sync backend is the hosted Tuvora one. */
internal suspend fun hostedAccessTokenOrNull(): String? {
    val auth = AuthRepository.state.value as? AuthState.Authenticated ?: return null
    if (auth.isAnonymous) return null
    if (!SyncBackendDefaults.hosted().hasSameConnectionIdentity(SyncBackendRepository.selectedBackend)) return null
    return runCatching { SupabaseProvider.client.auth.currentAccessTokenOrNull() }.getOrNull()
}
