package com.nuvio.app.features.servers

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Read-only access to what the released fork stack stored on Android: the plain preferences file "nuvio_mediaserver" (entries per
 * profile, pinned certificates) and "nuvio_mediaserver_tokens" (Android Keystore AES-256-GCM, key alias below, each value bound to its
 * item key as AAD). Nothing is ever removed on a failed decrypt - the fork's own store did that; the importer must not.
 */
internal actual object PlatformLegacyMediaServerSource : LegacyMediaServerSource {
    private const val KEY_ALIAS = "com.nuvio.media.mediaserver.tokens.v1"
    private const val ENTRIES_PREFIX = "entries_"
    private const val TRUST_KEY = "trust"
    private const val MIGRATED_KEY = "migrated_to_servers_v1"
    private var plain: SharedPreferences? = null
    private var secure: SharedPreferences? = null

    fun initialize(context: Context) {
        val app = context.applicationContext
        plain = app.getSharedPreferences("nuvio_mediaserver", Context.MODE_PRIVATE)
        secure = app.getSharedPreferences("nuvio_mediaserver_tokens", Context.MODE_PRIVATE)
    }

    override fun isAvailable(): Boolean = plain != null

    override fun entryProfileIds(): List<Int> =
        plain?.all?.keys.orEmpty().filter { it.startsWith(ENTRIES_PREFIX) }.mapNotNull { it.removePrefix(ENTRIES_PREFIX).toIntOrNull() }.sorted()

    override fun entriesJson(profileId: Int): String? = plain?.getString("$ENTRIES_PREFIX$profileId", null)

    override fun trustJson(): String? = plain?.getString(TRUST_KEY, null)

    override fun secret(key: String): String? {
        val value = secure?.getString("item.$key", null) ?: return null
        return try {
            val parts = value.split('.', limit = 2)
            require(parts.size == 2)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD("$KEY_ALIAS:$key".toByteArray(Charsets.UTF_8))
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override fun isMigrated(): Boolean = plain?.getBoolean(MIGRATED_KEY, false) == true

    override fun markMigrated() {
        plain?.edit()?.putBoolean(MIGRATED_KEY, true)?.commit()
    }

    override fun eraseAll() {
        plain?.edit()?.clear()?.putBoolean(MIGRATED_KEY, true)?.commit()
        secure?.edit()?.clear()?.commit()
    }
}
