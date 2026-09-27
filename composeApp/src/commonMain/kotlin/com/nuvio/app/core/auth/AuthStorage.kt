package com.nuvio.app.core.auth

internal expect object AuthStorage {
    fun loadAnonymousUserId(): String?
    fun saveAnonymousUserId(userId: String)
    fun clearAnonymousUserId()
    fun loadValue(key: String): String?
    fun saveValue(key: String, value: String)
    fun removeValue(key: String)
}

/**
 * Remembers which account the data on this device belongs to, so a lost session can keep that data
 * (D1) without it ever being merged into a different account that signs in afterwards. Cleared only
 * by a deliberate sign-out / delete / server switch, together with the data itself.
 */
internal object LocalDataOwnerStore {
    private const val KEY_OWNER_USER_ID = "local_data_owner_user_id"
    private const val KEY_OWNER_EMAIL = "local_data_owner_email"
    private const val KEY_SESSION_LOST_NOTICE_ACK = "session_lost_notice_acknowledged"

    fun load(): LocalDataOwner? {
        val userId = runCatching { AuthStorage.loadValue(KEY_OWNER_USER_ID) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val email = runCatching { AuthStorage.loadValue(KEY_OWNER_EMAIL) }.getOrNull()?.takeIf { it.isNotBlank() }
        return LocalDataOwner(userId = userId, email = email)
    }

    /** Records [owner] as signed in; a fresh sign-in also re-arms the lost-session notice. */
    fun record(owner: LocalDataOwner) {
        runCatching {
            if (load() != owner) {
                AuthStorage.saveValue(KEY_OWNER_USER_ID, owner.userId)
                if (owner.email.isNullOrBlank()) {
                    AuthStorage.removeValue(KEY_OWNER_EMAIL)
                } else {
                    AuthStorage.saveValue(KEY_OWNER_EMAIL, owner.email)
                }
            }
            if (AuthStorage.loadValue(KEY_SESSION_LOST_NOTICE_ACK) != null) {
                AuthStorage.removeValue(KEY_SESSION_LOST_NOTICE_ACK)
            }
        }
    }

    fun clear() {
        runCatching {
            AuthStorage.removeValue(KEY_OWNER_USER_ID)
            AuthStorage.removeValue(KEY_OWNER_EMAIL)
            AuthStorage.removeValue(KEY_SESSION_LOST_NOTICE_ACK)
        }
    }

    fun isNoticeAcknowledged(): Boolean =
        runCatching { AuthStorage.loadValue(KEY_SESSION_LOST_NOTICE_ACK) == "true" }.getOrDefault(false)

    fun acknowledgeNotice() {
        runCatching { AuthStorage.saveValue(KEY_SESSION_LOST_NOTICE_ACK, "true") }
    }
}
