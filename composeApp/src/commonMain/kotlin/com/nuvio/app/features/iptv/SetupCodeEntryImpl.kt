package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.SetupCodeEntry

/**
 * The IPTV feature's end of the [SetupCodeEntry] port on Desktop: everything delegates to the shared
 * controller, and the master-detail IPTV page is told which pane to show (the preview for a held code, the
 * code field for "I have a setup code").
 */
internal object SetupCodeEntryImpl : SetupCodeEntry {
    override fun acceptLinkedCode(link: String): Boolean {
        val accepted = SetupCodeController.shared.acceptLinkedCode(link)
        if (accepted) IptvSettingsPane.openPreview()
        return accepted
    }

    override fun holdLinkedCode(link: String): Boolean = SetupCodeController.shared.holdLinkedCode(link)

    override fun hasHeldCode(): Boolean = SetupCodeController.shared.hasHeldCode()

    override fun acceptHeldCode(): Boolean {
        val accepted = SetupCodeController.shared.acceptHeldCode()
        if (accepted) IptvSettingsPane.openPreview()
        return accepted
    }

    override fun takeResumeAfterSignIn(): Boolean {
        val resume = SetupCodeController.shared.takeResumeAfterSignIn()
        if (resume) IptvSettingsPane.openPreview()
        return resume
    }

    override fun prepareCodeEntryPage() {
        XtreamRepository.clearError()
        IptvSettingsPane.openAdd()
    }
}
