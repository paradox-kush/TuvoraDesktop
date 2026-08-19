package com.nuvio.app.core.contracts

/**
 * A feature that clears its device-local state on sign-out / account wipe (seam: sign-out firewall).
 * Fork surfaces register here instead of the shared LocalAccountDataCleaner naming them. Clears are
 * independent, so registry order is immaterial.
 */
interface LocalStateCleaner {
    val name: String
    fun clearLocalState()
}

object LocalStateCleanerRegistry {
    private val byName = LinkedHashMap<String, LocalStateCleaner>()
    fun register(cleaner: LocalStateCleaner) {
        require(byName.put(cleaner.name, cleaner) == null) { "duplicate LocalStateCleaner: ${cleaner.name}" }
    }
    val all: List<LocalStateCleaner> get() = byName.values.toList()
}
