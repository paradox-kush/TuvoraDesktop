@file:OptIn(ExperimentalForeignApi::class)

package com.nuvio.app.core.rec

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

private const val QUEUE_FILE = "rec-events-queue.jsonl"

internal actual object RecEventStorage {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    actual fun loadString(key: String): String? = defaults.stringForKey(key)

    actual fun saveString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    actual fun removeString(key: String) {
        defaults.removeObjectForKey(key)
    }

    actual fun loadBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(key) == null) default else defaults.boolForKey(key)

    actual fun saveBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    actual fun loadLong(key: String, default: Long): Long =
        if (defaults.objectForKey(key) == null) default else defaults.integerForKey(key)

    actual fun saveLong(key: String, value: Long) {
        defaults.setInteger(value, forKey = key)
    }

    actual fun loadQueue(): String? = runCatching {
        val path = queuePath() ?: return null
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
    }.getOrNull()

    actual fun saveQueue(contents: String?) {
        runCatching {
            val path = queuePath() ?: return
            if (contents == null) {
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            } else {
                // NSString.create is the supported bridge; a Kotlin String cast to NSString is
                // not valid in Kotlin/Native.
                NSString.create(string = contents).writeToFile(
                    path,
                    atomically = true,
                    encoding = NSUTF8StringEncoding,
                    error = null,
                )
            }
        }
    }

    /** Documents rather than Caches: unsent events should survive storage pressure. */
    private fun queuePath(): String? {
        val documents = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: return null
        return "$documents/$QUEUE_FILE"
    }
}

internal actual val recAppIdentifier: String = "mobile-ios"

internal actual fun recNowMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
