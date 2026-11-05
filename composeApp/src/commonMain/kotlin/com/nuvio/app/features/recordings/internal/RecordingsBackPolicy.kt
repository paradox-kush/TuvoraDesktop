package com.nuvio.app.features.recordings.internal

internal enum class RecordingsBackAction { CLOSE_SHEET, LEAVE_PAGE }

/** What Back (Escape, the mouse Back button) does on the Recordings page: an open details sheet closes first. */
internal object RecordingsBackPolicy {
    fun actionFor(sheetOpen: Boolean): RecordingsBackAction =
        if (sheetOpen) RecordingsBackAction.CLOSE_SHEET else RecordingsBackAction.LEAVE_PAGE
}
