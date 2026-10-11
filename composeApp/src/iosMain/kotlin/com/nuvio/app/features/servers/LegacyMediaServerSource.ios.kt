package com.nuvio.app.features.servers

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
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
import platform.Foundation.NSUserDefaults
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData

/**
 * Read-only access to what the released fork stack stored on iOS and Apple TV: NSUserDefaults (`mediaserver_entries_<profileId>`,
 * `mediaserver_trust`) and the Keychain (service `com.nuvio.media.mediaserver`, account `item.<key>`). The marker and the wipe use
 * NSUserDefaults / the same Keychain service; the importer never deletes legacy values except on an account wipe.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformLegacyMediaServerSource : LegacyMediaServerSource {
    private const val SERVICE = "com.nuvio.media.mediaserver"
    private const val ENTRIES_PREFIX = "mediaserver_entries_"
    private const val TRUST_KEY = "mediaserver_trust"
    private const val MIGRATED_KEY = "mediaserver_migrated_to_servers_v1"
    private val defaults get() = NSUserDefaults.standardUserDefaults

    override fun isAvailable(): Boolean = true

    override fun entryProfileIds(): List<Int> =
        defaults.dictionaryRepresentation().keys.mapNotNull { (it as? String)?.takeIf { key -> key.startsWith(ENTRIES_PREFIX) } }
            .mapNotNull { it.removePrefix(ENTRIES_PREFIX).toIntOrNull() }.sorted()

    override fun entriesJson(profileId: Int): String? = defaults.stringForKey("$ENTRIES_PREFIX$profileId")

    override fun trustJson(): String? = defaults.stringForKey(TRUST_KEY)

    override fun secret(key: String): String? = runCatching { keychain(key) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status != errSecSuccess) return@memScoped null
            val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
            try {
                val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
                ByteArray(CFDataGetLength(data).toInt()) { bytes[it].toByte() }.decodeToString()
            } finally {
                CFRelease(data)
            }
        }
    } }.getOrNull()

    override fun isMigrated(): Boolean = defaults.boolForKey(MIGRATED_KEY)

    override fun markMigrated() {
        defaults.setBool(true, forKey = MIGRATED_KEY)
    }

    override fun eraseAll() {
        entryProfileIds().forEach { defaults.removeObjectForKey("$ENTRIES_PREFIX$it") }
        defaults.removeObjectForKey(TRUST_KEY)
        defaults.setBool(true, forKey = MIGRATED_KEY)
        runCatching { keychain(null) { query -> SecItemDelete(query) } }
    }

    private inline fun <T> keychain(key: String?, block: (CFMutableDictionaryRef) -> T): T {
        val service = CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8) ?: error("service")
        val account = key?.let { CFStringCreateWithCString(null, "item.$it", kCFStringEncodingUTF8) }
        val query = CFDictionaryCreateMutable(null, 0L, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr) ?: error("query")
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
