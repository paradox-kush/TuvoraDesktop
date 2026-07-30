package com.nuvio.app.core.network

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal actual object SyncBackendStorage {
    private const val KEY_SELECTION_PAYLOAD = "selection_payload_v1"
    private val store by lazy { DesktopStorage.store("nuvio_sync_backend") }

    actual fun loadSelectionPayload(): String? = store.getString(KEY_SELECTION_PAYLOAD)

    actual fun saveSelectionPayload(payload: String) {
        store.putString(KEY_SELECTION_PAYLOAD, payload)
    }
}

private val syncBackendHttpClient = OkHttpClient.Builder()
    .dns(DesktopIPv4FirstDns())
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .proxy(Proxy.NO_PROXY)
    .build()

internal actual suspend fun fetchSyncBackendManifestText(url: String): String =
    withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()

        syncBackendHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Sync backend manifest request failed with HTTP ${response.code}")
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: error("Sync backend manifest response was empty")
        }
    }
