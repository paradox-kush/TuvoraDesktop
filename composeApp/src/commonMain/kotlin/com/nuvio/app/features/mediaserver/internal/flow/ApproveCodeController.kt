package com.nuvio.app.features.mediaserver.internal.flow

import com.nuvio.app.features.mediaserver.api.MediaServerEntry
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.MediaServerServices
import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.MediaBrowserDialect
import com.nuvio.app.features.mediaserver.internal.policy.QuickConnectPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class ApproveStatus { IDLE, BUSY, APPROVED, INVALID_CODE, CODE_NOT_FOUND, SIGN_IN_AGAIN, UNREACHABLE }

internal data class ApproveState(
    /** Signed-in servers of a product that has Quick Connect (Jellyfin). */
    val servers: List<MediaServerEntry> = emptyList(),
    val selectedKey: String? = null,
    val code: String = "",
    val status: ApproveStatus = ApproveStatus.IDLE,
)

/** Which servers can approve another device's Quick Connect code: signed in HERE, enabled, with an address, and a product that has Quick Connect. */
internal object ApproveEligibility {
    fun of(entry: MediaServerEntry, services: MediaServerServices): Boolean =
        entry.enabled && MediaBrowserDialect.of(entry.type).supportsQuickConnect && services.isSignedIn(entry) && !entry.address.isNullOrBlank()

    fun filter(entries: List<MediaServerEntry>, services: MediaServerServices): List<MediaServerEntry> = entries.filter { of(it, services) }

    fun any(entries: List<MediaServerEntry>, services: MediaServerServices): Boolean = entries.any { of(it, services) }
}

/**
 * "Approve a code" (design 5.3, the Swiftfin precedent): this device is already signed in to a Jellyfin server and
 * authorises the code another device (a TV) is showing. The approval is made with THIS device's session; the other
 * device exchanges its own secret for its OWN token - tokens are device-bound and never shared.
 */
internal class ApproveCodeController(
    private val services: MediaServerServices,
    entries: () -> List<MediaServerEntry>,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(initial(entries()))
    val state: StateFlow<ApproveState> = mutable.asStateFlow()

    private fun initial(all: List<MediaServerEntry>): ApproveState {
        val eligible = ApproveEligibility.filter(all, services)
        return ApproveState(servers = eligible, selectedKey = eligible.firstOrNull()?.key)
    }

    fun select(key: String) = mutable.update { it.copy(selectedKey = key, status = ApproveStatus.IDLE) }

    fun setCode(text: String) = mutable.update { it.copy(code = text.take(MAX_TYPED), status = ApproveStatus.IDLE) }

    fun approve() {
        val s = mutable.value
        if (s.status == ApproveStatus.BUSY) return
        val code = QuickConnectPolicy.normalizeTypedCode(s.code)
        if (code == null) {
            mutable.update { it.copy(status = ApproveStatus.INVALID_CODE) }
            return
        }
        val entry = s.servers.firstOrNull { it.key == s.selectedKey } ?: return
        val client = services.clientFor(entry)
        if (client == null) {
            mutable.update { it.copy(status = ApproveStatus.SIGN_IN_AGAIN) }
            return
        }
        mutable.update { it.copy(status = ApproveStatus.BUSY) }
        scope.launch {
            val outcome = try {
                client.authorizeQuickConnect(code)
                ApproveStatus.APPROVED
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaServerException) {
                statusFor(e).also { if (it == ApproveStatus.SIGN_IN_AGAIN) services.onUnauthorized(entry.serverKey) }
            }
            mutable.update { it.copy(status = outcome, code = if (outcome == ApproveStatus.APPROVED) "" else it.code) }
        }
    }

    companion object {
        private const val MAX_TYPED = 12

        /** What a failed authorise means to the person: 401/403 = this device's own session is gone; 400/404 = the code is wrong or lapsed. */
        fun statusFor(error: MediaServerException): ApproveStatus = when {
            error is MediaServerException.Http && error.isUnauthorized -> ApproveStatus.SIGN_IN_AGAIN
            error is MediaServerException.Http && (error.isNotFound || error.status == 400) -> ApproveStatus.CODE_NOT_FOUND
            else -> ApproveStatus.UNREACHABLE
        }
    }
}
