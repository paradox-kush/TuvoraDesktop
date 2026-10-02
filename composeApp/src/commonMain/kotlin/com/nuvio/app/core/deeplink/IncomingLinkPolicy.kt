package com.nuvio.app.core.deeplink

/**
 * Which incoming intent link an Android activity acts on (security M2). Pure, so the rule is tested.
 *
 * The intent that started an activity is replayed by the OS every time the activity is recreated (locale,
 * dark mode, font scale, process restore from Recents). Acting on it again would re-arm a Cancelled or spent
 * setup code and restart its 30 minute clock, so a launch handles its link once, and the data is cleared
 * from the intent afterwards.
 */
internal object IncomingLinkPolicy {
    /** The link a freshly created activity should handle: none when it is a restoration of an earlier instance. */
    fun linkToHandle(restoredFromSavedState: Boolean, dataString: String?): String? =
        if (restoredFromSavedState) null else dataString?.trim()?.takeIf { it.isNotEmpty() }

    /** A new intent delivered to a running activity is always a fresh request. */
    fun linkFromNewIntent(dataString: String?): String? = dataString?.trim()?.takeIf { it.isNotEmpty() }
}
