package com.nuvio.app.features.sportsdetail.internal

/** Named gap: Desktop does not read the OS reduce-motion setting yet (macOS would need a native bridge call). */
internal actual fun platformReduceMotion(): Boolean = false
