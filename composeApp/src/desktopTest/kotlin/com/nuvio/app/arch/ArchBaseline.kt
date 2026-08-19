package com.nuvio.app.arch

/**
 * Frozen crossing surface as of Phase 0 (Desktop twin). Generated from the exact R2b+R2d rule for
 * THIS repo (Desktop's commonMain diverges from Mobile's ~107 files, so its crossing set differs).
 * Ratchet: only shrinks. Do not add entries to silence a rule.
 *
 * S10a cleared the memory crossings; S10b cleared PlayerEngine.android/ios (rejoinsLiveEdge moved to
 * neutral features.player). Remaining: MainActivity (startup-DB init, S10c).
 */
object ArchBaseline {
    val crossings: Set<String> = setOf(
        "androidMain/kotlin/com/nuvio/app/MainActivity.kt",
    )
}
