package com.nuvio.app.features.mediaserver.internal.store

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM sealing of one value under a master key, bound to its item key as additional authenticated data
 * (a blob moved to another item - or another file - does not open). Output: `base64(iv).base64(ciphertext+tag)`.
 * Pure JVM; the master key's custody is [MasterKeyProvider]'s business.
 */
internal object DesktopSecretCipher {
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun seal(key: ByteArray, aad: String, plaintext: String): String {
        require(key.size == KEY_BYTES)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return "${b64.encodeToString(iv)}.${b64.encodeToString(sealed)}"
    }

    /** The plaintext, or null when the blob is malformed, tampered with, bound to another item, or sealed under another key. */
    fun open(key: ByteArray, aad: String, sealed: String): String? = try {
        val parts = sealed.split('.', limit = 2)
        require(parts.size == 2 && key.size == KEY_BYTES)
        val b64 = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, b64.decode(parts[0])))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        cipher.doFinal(b64.decode(parts[1])).toString(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}
