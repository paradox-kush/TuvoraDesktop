package com.nuvio.app.core.sync

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.isLocalOnly

/**
 * Whether a write to Nuvio Sync can go out right now.
 *
 * Every `sync_push_*` / `sync_delete_*` RPC is `revoke all … from public, anon` + `grant execute …
 * to authenticated`, so a call made without a session doesn't fail as "not signed in" — PostgREST
 * runs it as the `anon` role and Postgres answers
 *
 *     permission denied for function sync_push_watch_progress   (SQLSTATE 42501)
 *
 * which is the exact trap [isLocalOnly] was written to document. The pull side has always checked
 * this (see [SyncManager.requestForegroundPull] and its twins). The push side never did: scrobbles
 * fire from playback rather than from a user action, so they kept firing after a session went away
 * — each one a wasted round-trip that logged an error and dropped the write.
 */
internal object SyncSession {

    /** True when an RPC will carry a real user's JWT. */
    fun canPush(): Boolean = !AuthRepository.state.value.isLocalOnly

    /** Throws [SyncNotAuthenticatedException] unless [canPush]. */
    fun requirePushable() {
        if (!canPush()) throw SyncNotAuthenticatedException()
    }
}

/**
 * A push was attempted with no usable session, and was refused locally instead of being sent as
 * `anon` for the server to reject.
 *
 * Thrown rather than returned so callers keep treating it as "not pushed": every push site already
 * leaves the item in its dirty set when the push fails, which is exactly the behaviour wanted here
 * — the write stays queued and goes out on the next full sync after sign-in. Returning normally
 * would mark it pushed and lose it.
 *
 * Stays an [IllegalStateException] so existing `runCatching`/catch sites behave as before; call
 * sites that want to log it quietly can test for this type.
 */
internal class SyncNotAuthenticatedException :
    IllegalStateException("Not signed in — sync push skipped and left queued")
