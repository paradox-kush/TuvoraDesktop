package com.nuvio.app.features.servers

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/** A Quick Connect request as the server issued it: [code] is shown to the user, [secret] never leaves the device except to the same server. */
class QuickConnectChallenge(
    val code: String,
    val secret: String,
    val baseUrl: String,
    val serverId: String,
    val serverName: String,
) {
    override fun toString(): String = "QuickConnectChallenge(server=$serverId)"
}

enum class QuickConnectPoll { PENDING, AUTHORISED, EXPIRED }

enum class QuickConnectFailure {
    /** Quick Connect is switched off on the server, or this server has none (Emby). */
    DISABLED,

    /** Repeated transport failures. */
    UNREACHABLE,

    /** The certificate must be trusted (or re-trusted) first; the driver exposes the exception. */
    CERTIFICATE,

    /** Anything else the server answered. */
    SERVER_ERROR,
}

/**
 * Cadence of one Quick Connect sign-in: a code with a countdown, polled gently until someone approves it, bounded by the
 * server's request lifetime. Jellyfin's QuickConnectManager expires a request after 10 minutes (verified against the
 * server source); 6-digit codes. Pure - the caller supplies the clock.
 */
internal object QuickConnectPolicy {
    const val REQUEST_LIFETIME_MS = 10 * 60_000L
    const val FIRST_POLL_MS = 2_000L
    const val STEADY_POLL_MS = 4_000L

    /** Consecutive transport failures tolerated before giving up (a flaky LAN is not a failed sign-in). */
    const val MAX_CONSECUTIVE_FAILURES = 5

    fun pollDelayMs(attempt: Int): Long = if (attempt < 3) FIRST_POLL_MS else STEADY_POLL_MS

    fun remainingMs(startedAtMs: Long, nowMs: Long): Long = (REQUEST_LIFETIME_MS - (nowMs - startedAtMs)).coerceAtLeast(0L)

    fun isExpired(startedAtMs: Long, nowMs: Long): Boolean = remainingMs(startedAtMs, nowMs) == 0L

    /** `m:ss` for the countdown ("9:41"). */
    fun countdownLabel(remainingMs: Long): String {
        val totalSeconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
        return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }

    /** Digits grouped for legibility ("123 456"); the wire form stays ungrouped. */
    fun displayCode(code: String): String {
        val digits = code.filter { !it.isWhitespace() }
        return if (digits.length == 6) "${digits.substring(0, 3)} ${digits.substring(3)}" else digits
    }
}

sealed interface QuickConnectState {
    data object Idle : QuickConnectState
    data object Starting : QuickConnectState

    /** A code is on screen and the server is being polled. */
    data class Waiting(val code: String, val startedAtMs: Long, val attempt: Int, val failures: Int) : QuickConnectState

    /** Approved: the secret is ready to be exchanged for this device's own token. */
    data object Authorised : QuickConnectState
    data object Expired : QuickConnectState
    data class Failed(val reason: QuickConnectFailure) : QuickConnectState
}

sealed interface QuickConnectEvent {
    /** Start, or Retry from a terminal state. */
    data object Start : QuickConnectEvent
    data class CodeIssued(val code: String, val nowMs: Long) : QuickConnectEvent
    data class StartFailed(val reason: QuickConnectFailure) : QuickConnectEvent
    data class PollPending(val nowMs: Long) : QuickConnectEvent
    data object PollAuthorised : QuickConnectEvent
    data object PollExpired : QuickConnectEvent

    /** [transient] = a transport problem worth retrying; false = the server refused (Quick Connect off). */
    data class PollFailed(val nowMs: Long, val transient: Boolean) : QuickConnectEvent
    data class Tick(val nowMs: Long) : QuickConnectEvent

    /** A problem the sign-in cannot continue past (a certificate to trust): Waiting ends as Failed. */
    data class Abort(val reason: QuickConnectFailure) : QuickConnectEvent

    /** The exchange of an approved secret failed. */
    data class ExchangeFailed(val reason: QuickConnectFailure) : QuickConnectEvent
    data object Cancel : QuickConnectEvent
}

/** The Quick Connect sign-in as a pure state machine; I/O lives in [QuickConnectDriver]. */
internal object QuickConnectMachine {
    fun reduce(state: QuickConnectState, event: QuickConnectEvent): QuickConnectState = when (event) {
        QuickConnectEvent.Cancel -> QuickConnectState.Idle
        QuickConnectEvent.Start -> when (state) {
            QuickConnectState.Idle,
            QuickConnectState.Authorised,
            QuickConnectState.Expired,
            is QuickConnectState.Failed -> QuickConnectState.Starting
            else -> state
        }
        is QuickConnectEvent.CodeIssued ->
            if (state == QuickConnectState.Starting) QuickConnectState.Waiting(event.code, event.nowMs, attempt = 0, failures = 0) else state
        is QuickConnectEvent.StartFailed ->
            if (state == QuickConnectState.Starting) QuickConnectState.Failed(event.reason) else state
        is QuickConnectEvent.ExchangeFailed ->
            if (state == QuickConnectState.Authorised) QuickConnectState.Failed(event.reason) else state
        is QuickConnectEvent.Abort -> waitingOnly(state) { QuickConnectState.Failed(event.reason) }
        is QuickConnectEvent.Tick -> waitingOnly(state) { w ->
            if (QuickConnectPolicy.isExpired(w.startedAtMs, event.nowMs)) QuickConnectState.Expired else w
        }
        is QuickConnectEvent.PollPending -> waitingOnly(state) { w ->
            if (QuickConnectPolicy.isExpired(w.startedAtMs, event.nowMs)) QuickConnectState.Expired
            else w.copy(attempt = w.attempt + 1, failures = 0)
        }
        QuickConnectEvent.PollAuthorised -> waitingOnly(state) { QuickConnectState.Authorised }
        QuickConnectEvent.PollExpired -> waitingOnly(state) { QuickConnectState.Expired }
        is QuickConnectEvent.PollFailed -> waitingOnly(state) { w ->
            when {
                !event.transient -> QuickConnectState.Failed(QuickConnectFailure.DISABLED)
                QuickConnectPolicy.isExpired(w.startedAtMs, event.nowMs) -> QuickConnectState.Expired
                w.failures + 1 >= QuickConnectPolicy.MAX_CONSECUTIVE_FAILURES -> QuickConnectState.Failed(QuickConnectFailure.UNREACHABLE)
                else -> w.copy(attempt = w.attempt + 1, failures = w.failures + 1)
            }
        }
    }

    /** Delay before the next poll, or null when nothing is to be polled. */
    fun nextDelayMs(state: QuickConnectState): Long? =
        (state as? QuickConnectState.Waiting)?.let { QuickConnectPolicy.pollDelayMs(it.attempt) }

    fun isTerminal(state: QuickConnectState): Boolean = when (state) {
        QuickConnectState.Authorised, QuickConnectState.Expired, is QuickConnectState.Failed -> true
        else -> false
    }

    private inline fun waitingOnly(state: QuickConnectState, block: (QuickConnectState.Waiting) -> QuickConnectState): QuickConnectState =
        if (state is QuickConnectState.Waiting) block(state) else state
}

/**
 * Runs one Quick Connect sign-in against [provider]. [start] issues a code; [run] polls until a terminal state and is meant
 * to be called from a lifecycle-bound scope (`repeatOnLifecycle(RESUMED)`): it ends with the scope, is safe to call again
 * on resume (it continues the same request), never overlaps itself, and is bounded by the request lifetime - there is no
 * unbounded loop and no poll while the screen is not visible. A terminal Expired/Failed is re-asked only by [start] (Retry).
 */
internal class QuickConnectDriver(
    private val provider: ServerProvider,
    private val address: String,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutable = MutableStateFlow<QuickConnectState>(QuickConnectState.Idle)
    val state: StateFlow<QuickConnectState> = mutable.asStateFlow()

    /** Set while [state] is `Failed(CERTIFICATE)`: what the user must decide to trust. */
    @Volatile
    var certificate: ServerCertificateException? = null
        private set

    private var challenge: QuickConnectChallenge? = null

    /** The certificate decision the screen must put to the user now (the request stopped on it), else null. */
    fun certificateToPrompt(): ServerCertificateException? =
        if ((mutable.value as? QuickConnectState.Failed)?.reason == QuickConnectFailure.CERTIFICATE) certificate else null

    private fun send(event: QuickConnectEvent) = mutable.update { QuickConnectMachine.reduce(it, event) }

    /** Start, or Retry. A no-op while a request is already running. */
    suspend fun start() {
        send(QuickConnectEvent.Start)
        if (mutable.value != QuickConnectState.Starting) return
        certificate = null
        challenge = null
        try {
            val issued = provider.quickConnectStart(address)
            challenge = issued
            send(QuickConnectEvent.CodeIssued(issued.code, nowMs()))
        } catch (error: CancellationException) {
            send(QuickConnectEvent.Cancel)
            throw error
        } catch (error: Throwable) {
            send(QuickConnectEvent.StartFailed(failureOf(error)))
        }
    }

    /** Polls until the state is no longer Waiting. */
    suspend fun run() {
        val current = challenge ?: return
        send(QuickConnectEvent.Tick(nowMs()))
        // Bounded: every pass either keeps Waiting (and the machine expires it at the request lifetime) or ends the loop.
        var wait = QuickConnectMachine.nextDelayMs(mutable.value)
        while (wait != null) {
            delay(wait)
            if (mutable.value !is QuickConnectState.Waiting) return
            val event = try {
                when (provider.quickConnectPoll(current)) {
                    QuickConnectPoll.PENDING -> QuickConnectEvent.PollPending(nowMs())
                    QuickConnectPoll.AUTHORISED -> QuickConnectEvent.PollAuthorised
                    QuickConnectPoll.EXPIRED -> QuickConnectEvent.PollExpired
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error is ServerCertificateException) {
                    certificate = error
                    QuickConnectEvent.Abort(QuickConnectFailure.CERTIFICATE)
                } else {
                    val refused = (error as? ServerException)?.failure.let { it == ServerFailure.AUTH_REQUIRED || it == ServerFailure.FORBIDDEN }
                    QuickConnectEvent.PollFailed(nowMs(), transient = !refused)
                }
            }
            send(event)
            wait = QuickConnectMachine.nextDelayMs(mutable.value)
        }
    }

    /** After [QuickConnectState.Authorised]: exchanges the secret for this device's own token and stores the connection (device-local token). */
    suspend fun complete(): ServerConnection {
        val current = challenge ?: throw ServerException(ServerFailure.FAILED)
        return try {
            ServerRepository.connectWithQuickConnect(provider, current)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is ServerCertificateException) certificate = error
            send(QuickConnectEvent.ExchangeFailed(failureOf(error)))
            throw error
        }
    }

    fun cancel() {
        challenge = null
        send(QuickConnectEvent.Cancel)
    }

    private fun failureOf(error: Throwable): QuickConnectFailure {
        if (error is ServerCertificateException) {
            certificate = error
            return QuickConnectFailure.CERTIFICATE
        }
        return when ((error as? ServerException)?.failure) {
            ServerFailure.AUTH_REQUIRED, ServerFailure.FORBIDDEN, ServerFailure.UNSUPPORTED, ServerFailure.NOT_FOUND -> QuickConnectFailure.DISABLED
            ServerFailure.UNREACHABLE -> QuickConnectFailure.UNREACHABLE
            else -> QuickConnectFailure.SERVER_ERROR
        }
    }
}
