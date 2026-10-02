package com.nuvio.app.features.addons

/** What the Add-ons screen says about whether the device's list has reached the account (UX71). */
internal enum class AddonSyncStatus {
    /** Nothing to say: no account to sync to, or this profile shows the primary profile's add-ons. */
    NotApplicable,

    /** The list matches what this device and the account last agreed on. */
    Synced,

    /** A push or retry is on its way; the marker stays hidden so an edit doesn't flash it. */
    Syncing,

    /** Local edits differ from the last-synced list (skipped or failed push): show "Not synced yet" + retry. */
    NotSynced,
}

/**
 * Decides the "Not synced yet" marker on the Add-ons screen.
 *
 * [lastSynced] is the [AddonSyncMerge] baseline — the list this device and the server last agreed on, `null` when
 * this device never synced. Order counts: a reorder is pushed (`sort_order`), so an unpushed reorder is unsynced.
 * [key] identifies the same add-on across URL spellings, as in [AddonSyncMerge].
 */
internal object AddonSyncStatusPolicy {
    fun status(
        local: List<String>,
        lastSynced: List<String>?,
        hasAccount: Boolean,
        followsPrimaryProfile: Boolean,
        syncInFlight: Boolean,
        key: (String) -> String = { it },
    ): AddonSyncStatus {
        if (!hasAccount || followsPrimaryProfile) return AddonSyncStatus.NotApplicable
        if (syncInFlight) return AddonSyncStatus.Syncing
        val localKeys = local.map(key).distinct()
        val syncedKeys = lastSynced?.map(key)?.distinct()
        return when {
            syncedKeys == null && localKeys.isEmpty() -> AddonSyncStatus.Synced
            localKeys == syncedKeys -> AddonSyncStatus.Synced
            else -> AddonSyncStatus.NotSynced
        }
    }
}
