package com.nuvio.app.core.network

import com.nuvio.app.core.storage.DesktopStorage

internal actual object ServerConfigurationStorage {
    private val store by lazy { DesktopServerConfigurationStore(DesktopStorage.store("server_configuration")) }

    actual fun loadCustom(): ServerConfiguration? = store.loadCustom()

    actual fun saveCustom(configuration: ServerConfiguration): Boolean = store.saveCustom(configuration)

    actual fun useOfficial(): Boolean = store.useOfficial()
}

/** The custom-server keys on one properties file; a class so tests run it on a temp file. */
internal class DesktopServerConfigurationStore(private val store: DesktopStorage.Store) {
    private val customEnabledKey = "custom_enabled"
    private val backendUrlKey = "backend_url"
    private val publishableKey = "publishable_key"
    private val emailPasswordAuthKey = "email_password_auth"
    private val tvLoginKey = "tv_login"
    private val watchPartyKey = "watch_party"
    private val discoveryUrlKey = "discovery_url"

    fun loadCustom(): ServerConfiguration? {
        if (store.getBoolean(customEnabledKey) != true) return null
        val backendUrl = store.getString(backendUrlKey)?.trim().orEmpty()
        val key = store.getString(publishableKey)?.trim().orEmpty()
        val emailPasswordAuth = store.getBoolean(emailPasswordAuthKey) ?: false
        val tvLogin = store.getBoolean(tvLoginKey) ?: false
        val watchParty = store.getBoolean(watchPartyKey) ?: false
        if (backendUrl.isBlank() || key.isBlank() || !emailPasswordAuth) return null
        return ServerConfiguration(
            backendUrl = backendUrl,
            publishableKey = key,
            capabilities = ServerCapabilities(
                emailPasswordAuth = emailPasswordAuth,
                tvLogin = tvLogin,
                watchParty = watchParty,
            ),
            isCustom = true,
            discoveryUrl = store.getString(discoveryUrlKey),
        )
    }

    fun saveCustom(configuration: ServerConfiguration): Boolean {
        store.putBoolean(customEnabledKey, true)
        store.putString(backendUrlKey, configuration.backendUrl)
        store.putString(publishableKey, configuration.publishableKey)
        store.putBoolean(emailPasswordAuthKey, configuration.capabilities.emailPasswordAuth)
        store.putBoolean(tvLoginKey, configuration.capabilities.tvLogin)
        store.putBoolean(watchPartyKey, configuration.capabilities.watchParty)
        store.putString(discoveryUrlKey, configuration.discoveryUrl)
        return true
    }

    fun useOfficial(): Boolean {
        store.removeAll(
            listOf(
                customEnabledKey,
                backendUrlKey,
                publishableKey,
                emailPasswordAuthKey,
                tvLoginKey,
                watchPartyKey,
                discoveryUrlKey,
            ),
        )
        return true
    }
}
