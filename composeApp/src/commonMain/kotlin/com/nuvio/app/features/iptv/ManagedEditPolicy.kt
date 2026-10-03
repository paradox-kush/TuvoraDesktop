package com.nuvio.app.features.iptv

/**
 * The guard against SILENT DETACH.
 *
 * The server silently detaches a managed row when a pushed row changes ANY provider-owned field:
 * `base_url / url / portal_url, username, password, mac_address, stalker_username, stalker_password,
 * backup_urls, epg_url, user_agent` (and the source type). An edit path that rebuilds an account from
 * form fields re-normalises the address (lowercases the host, drops a default port and a trailing slash,
 * trims), re-defaults the user agent and re-validates the backups — so merely RENAMING a managed
 * playlist used to change a byte and cost the customer their provider's updates without a word.
 *
 * For a managed playlist every edit, rename or option change builds its pushed account from the PULLED
 * one with those fields byte-identical: no re-normalising, no re-defaulting, no trimming.
 */
internal object ManagedEditPolicy {

    /**
     * [edited] with every provider-owned field put back exactly as [pulled] holds it. Safe to apply to
     * any candidate; for an unmanaged playlist it must not be called (an unmanaged edit is the user's).
     * The connection identity the provider also supplies (serial number, device ids, signature, model,
     * hw version, device-id flag)
     * rides along so a managed playlist is always reached exactly as installed.
     */
    fun lockProviderFields(pulled: XtreamAccount, edited: XtreamAccount): XtreamAccount = edited.copy(
        sourceType = pulled.sourceType,
        baseUrl = pulled.baseUrl,
        username = pulled.username,
        password = pulled.password,
        macAddress = pulled.macAddress,
        stalkerUsername = pulled.stalkerUsername,
        stalkerPassword = pulled.stalkerPassword,
        serialNumber = pulled.serialNumber,
        deviceId = pulled.deviceId,
        sendDeviceId = pulled.sendDeviceId,
        deviceId2 = pulled.deviceId2,
        signature = pulled.signature,
        stbModel = pulled.stbModel,
        hwVersion = pulled.hwVersion,
        backupUrls = pulled.backupUrls,
        epgUrl = pulled.epgUrl,
        userAgent = pulled.userAgent,
    )

    /**
     * The candidate for a managed playlist's rename: the pulled account with only its name replaced
     * (a blank [requestedName] keeps the current name). Built from the pulled account, never from
     * form fields, so there is nothing for normalisation to touch.
     */
    fun renamed(pulled: XtreamAccount, requestedName: String?): XtreamAccount =
        pulled.copy(name = requestedName?.trim()?.takeIf { it.isNotEmpty() } ?: pulled.name)

    /** True when [after] changed a field the provider owns relative to [before] (the detach trigger). */
    fun changesProviderFields(before: XtreamAccount, after: XtreamAccount): Boolean =
        lockProviderFields(before, after) != after
}
