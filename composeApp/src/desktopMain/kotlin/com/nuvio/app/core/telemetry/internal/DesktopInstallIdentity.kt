package com.nuvio.app.core.telemetry.internal

import java.util.UUID

/**
 * The one random id that names this Desktop install in analytics, kept beside the support code in the
 * `telemetry/` folder so both PostHog paths (the SDK and the exit-time POST) report the same person-less
 * device. It is a fresh random UUID: never derived from hardware, an account or a user name, and wiped with
 * the app data. The PostHog JVM SDK keeps its own id in memory only, so without this every launch was a new
 * install.
 *
 * [peek] only reads. [loadOrCreate] writes, and is called only once diagnostics are switched on, so an opted-out
 * install never gets an id file.
 */
internal class DesktopInstallIdentity(
    private val store: TelemetryFileStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun peek(): String? = store.read(FILE)?.trim()?.takeIf(::isValid)

    fun loadOrCreate(): String {
        peek()?.let { return it }
        val created = newId()
        store.writeAtomic(FILE, created)
        return created
    }

    private fun isValid(value: String): Boolean = UUID_SHAPE.matches(value)

    companion object {
        const val FILE = "install_id.txt"
        private val UUID_SHAPE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
