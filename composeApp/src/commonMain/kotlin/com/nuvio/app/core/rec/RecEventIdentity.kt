package com.nuvio.app.core.rec

import kotlin.random.Random

private const val KEY_DEVICE_ID = "device_id"

/** Brief backgrounding must not split a session, or impression dedupe resets and reshows. */
private const val SESSION_IDLE_RESET_MS = 30 * 60 * 1000L

private val UUID_REGEX =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

/**
 * Identity for the recommendation event stream: an install-scoped device id and a
 * foreground-session id. Twin of NuvioTV's `RecEventIdentity`.
 *
 * The device id is a random v4 UUID minted on first use — deliberately NOT an advertising id or
 * anything derived from hardware, so iOS needs no ATT prompt and the stream carries no cross-app
 * identity. Rotated when logging is re-enabled after an opt-out, so an opt-out cycle reads as a
 * new device rather than a gap in an existing one.
 */
object RecEventIdentity {
    private var cachedDeviceId: String? = null
    private var currentSessionId: String = randomUuid()
    private var lastActivityAtMs: Long = 0L

    fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val stored = RecEventStorage.loadString(KEY_DEVICE_ID)?.trim()
            ?.takeIf { UUID_REGEX.matches(it) }
        if (stored != null) {
            cachedDeviceId = stored
            return stored
        }
        val generated = randomUuid()
        RecEventStorage.saveString(KEY_DEVICE_ID, generated)
        cachedDeviceId = generated
        return generated
    }

    fun rotateDeviceId() {
        val generated = randomUuid()
        RecEventStorage.saveString(KEY_DEVICE_ID, generated)
        cachedDeviceId = generated
        currentSessionId = randomUuid()
    }

    /**
     * The session this event belongs to; rolls over after [SESSION_IDLE_RESET_MS] of no logging.
     * Callers must treat a changed value as a dedupe-set reset.
     */
    fun sessionId(nowMs: Long): String {
        if (lastActivityAtMs != 0L && nowMs - lastActivityAtMs > SESSION_IDLE_RESET_MS) {
            currentSessionId = randomUuid()
        }
        lastActivityAtMs = nowMs
        return currentSessionId
    }

    /** The current session without counting as activity, for dedupe bookkeeping. */
    fun peekSessionId(): String = currentSessionId
}

/**
 * RFC 4122 v4 from [Random]. Hand-rolled because `kotlin.uuid` is still experimental and this
 * only has to satisfy the edge function's UUID regex — it is an opaque install token, not a key
 * anything depends on being unguessable.
 */
internal fun randomUuid(): String {
    val bytes = ByteArray(16) { Random.nextInt(256).toByte() }
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte() // version 4
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // variant 10xx
    val hex = bytes.joinToString("") { byte ->
        val v = byte.toInt() and 0xFF
        "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
    }
    return buildString {
        append(hex, 0, 8); append('-')
        append(hex, 8, 12); append('-')
        append(hex, 12, 16); append('-')
        append(hex, 16, 20); append('-')
        append(hex, 20, 32)
    }
}
