package com.nuvio.app.features.plugins.runtime.js

import com.dokar.quickjs.QuickJs
import com.nuvio.app.features.plugins.runtime.configurePluginRuntime
import com.nuvio.app.features.plugins.runtime.pluginDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

internal class JsRuntime {
    suspend fun <T> use(block: suspend QuickJs.() -> T): T {
        val dispatcher = (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher)
            ?: pluginDispatcher
        // Engines are created and closed one at a time; only evaluation runs concurrently.
        // quickjs-kt (1.0.15, latest) reference-counts JNI globals shared by every engine
        // (initGlobals/releaseGlobals). A close that drops the count to zero racing another
        // engine's create leaves stale shared references: evaluate() then called back into the
        // wrong object (NPE "this.closed is null") or lost the result callback (hang until the
        // 60 s plugin timeout). Reproduced cold with 32 concurrent plugins; see
        // PluginRuntimeConcurrencyTest. Create/close take milliseconds, so plugins keep their
        // parallelism.
        val runtime = lifecycleLock.withLock { QuickJs.create(dispatcher) }
        try {
            runtime.configurePluginRuntime()
            return runtime.block()
        } finally {
            withContext(NonCancellable) { lifecycleLock.withLock { runtime.close() } }
        }
    }

    companion object {
        private val lifecycleLock = Mutex()

        @Volatile
        private var cachedPolyfillBytecode: ByteArray? = null

        @Volatile
        private var cachedCallBytecode: ByteArray? = null

        @Volatile
        private var cachedSettingsCallBytecode: ByteArray? = null

        fun polyfillBytecode(runtime: QuickJs): ByteArray =
            cachedPolyfillBytecode ?: runtime.compile(JsBindings.staticPolyfillCode, "polyfill.js", false).also {
                cachedPolyfillBytecode = it
            }

        fun callBytecode(runtime: QuickJs): ByteArray =
            cachedCallBytecode ?: runtime.compile(JsBindings.staticCallCode, "call.js", false).also {
                cachedCallBytecode = it
            }

        fun settingsCallBytecode(runtime: QuickJs): ByteArray =
            cachedSettingsCallBytecode
                ?: runtime.compile(JsBindings.staticSettingsCallCode, "settings-call.js", false).also {
                    cachedSettingsCallBytecode = it
                }
    }
}
