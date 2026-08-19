package com.nuvio.app.features.common.lifecycle

import kotlinx.atomicfu.atomic

/**
 * Process-global flag proving the feature-contribution bootstrap ran. Registration is PROCESS-INIT
 * state (see FeatureWiring.registerFeatureContributions), so the flag is atomic: it is written once
 * from the platform entry point (main thread today) and read from composition, but must not silently
 * break if registration is ever moved onto a background coroutine.
 */
object FeatureRegistry {
    private val initialized = atomic(false)

    val isInitialized: Boolean get() = initialized.value

    fun markInitialized() {
        initialized.value = true
    }

    /** Test-only: lets UI tests bypass app-init while still passing installFeatures' guard. */
    fun initializeForTests() {
        initialized.value = true
    }
}
