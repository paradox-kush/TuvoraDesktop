package com.nuvio.app.features.mediaserver.internal.store

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS / tvOS Keychain: one generic-password item per key under the media-server service,
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (readable by background refresh after the first unlock,
 * never migrated to another device, never in iCloud Keychain). Same construction as the MDBList credentials.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformSecureTokenStore : SecureTokenStore {
    private const val SERVICE = "com.nuvio.media.mediaserver"

    actual override fun read(key: String): String? = query(key) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            check(status == errSecSuccess) { "Unable to read the protected sign-in (OSStatus $status)" }
            val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
            try {
                val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
                ByteArray(CFDataGetLength(data).toInt()) { bytes[it].toByte() }.decodeToString()
            } finally {
                CFRelease(data)
            }
        }
    }

    actual override fun write(key: String, value: String?) = query(key) { query ->
        if (value == null) {
            checkDelete(SecItemDelete(query))
        } else {
            val bytes = value.encodeToByteArray().toUByteArray()
            require(bytes.isNotEmpty())
            val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong()) ?: error("Unable to encode the protected sign-in")
            val attributes = dictionary()
            try {
                CFDictionarySetValue(attributes, kSecValueData, data)
                val update = SecItemUpdate(query, attributes)
                if (update == errSecItemNotFound) {
                    CFDictionarySetValue(query, kSecValueData, data)
                    CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                    val added = SecItemAdd(query, null)
                    check(added == errSecSuccess) { "Unable to save the protected sign-in (OSStatus $added)" }
                } else {
                    check(update == errSecSuccess) { "Unable to update the protected sign-in (OSStatus $update)" }
                }
            } finally {
                CFRelease(attributes)
                CFRelease(data)
            }
        }
    }

    actual override fun clearAll() = query(null) { checkDelete(SecItemDelete(it)) }

    private fun checkDelete(status: Int) {
        check(status == errSecSuccess || status == errSecItemNotFound) { "Unable to clear the protected sign-in (OSStatus $status)" }
    }

    private fun dictionary(): CFMutableDictionaryRef =
        CFDictionaryCreateMutable(null, 0L, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: error("Unable to create the credential query")

    private inline fun <T> query(key: String?, block: (CFMutableDictionaryRef) -> T): T {
        val service = CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8) ?: error("Unable to encode the credential service")
        val account = key?.let { CFStringCreateWithCString(null, "item.$it", kCFStringEncodingUTF8) }
        val query = dictionary()
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            account?.let { CFDictionarySetValue(query, kSecAttrAccount, it) }
            return block(query)
        } finally {
            CFRelease(query)
            account?.let { CFRelease(it) }
            CFRelease(service)
        }
    }
}
