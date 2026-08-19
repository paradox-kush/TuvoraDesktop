package com.nuvio.app.core.rec

import com.nuvio.app.core.contracts.LocalStateCleaner

internal object RecLocalStateCleaner : LocalStateCleaner {
    override val name = "Rec event log"
    override fun clearLocalState() = RecEventLogger.resetLocalState()
}
