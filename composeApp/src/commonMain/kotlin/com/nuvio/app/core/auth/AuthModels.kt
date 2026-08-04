package com.nuvio.app.core.auth

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(
        val userId: String,
        val email: String?,
        val isAnonymous: Boolean,
    ) : AuthState
}

val AuthState.isLoggedIn: Boolean
    get() = this is AuthState.Authenticated

val AuthState.userId: String?
    get() = (this as? AuthState.Authenticated)?.userId

val AuthState.isAnonymous: Boolean
    get() = (this as? AuthState.Authenticated)?.isAnonymous == true

/**
 * True when there is no real account behind this state, so writes must stay on-device.
 *
 * Note this is NOT `isAnonymous`. That is false for [AuthState.Unauthenticated] and
 * [AuthState.Loading] as well as for real accounts, so branching on `!isAnonymous` sends a
 * session-less client down the server path, where every RPC goes out with the anon key as its
 * bearer and comes back `42501 permission denied`. Guard local-vs-server with this instead.
 */
val AuthState.isLocalOnly: Boolean
    get() = this !is AuthState.Authenticated || isAnonymous
