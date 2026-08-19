package com.nuvio.app.arch

/**
 * Frozen crossing surface as of Phase 0 (Desktop twin). Generated from the exact R2b+R2d rule for
 * THIS repo (Desktop's commonMain diverges from Mobile's ~107 files, so its crossing set differs).
 * Ratchet: only shrinks. Do not add entries to silence a rule.
 *
 * S10a (2026-08-19) cleared the 5 memory-only crossings behind the MemoryPort. Remaining:
 * MainActivity (startup-DB init, S10c) and PlayerEngine.android/ios (features.iptv.CatchUpPlayback, S10b).
 */
object ArchBaseline {
    val crossings: Set<String> = setOf(
        "androidMain/kotlin/com/nuvio/app/MainActivity.kt",
        "androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt",
        "iosMain/kotlin/com/nuvio/app/features/player/PlayerEngine.ios.kt",
    )
}
