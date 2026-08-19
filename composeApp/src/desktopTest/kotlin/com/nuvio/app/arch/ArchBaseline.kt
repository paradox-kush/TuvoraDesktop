package com.nuvio.app.arch

/**
 * Frozen crossing surface as of Phase 0 (Desktop twin). Generated from the exact R2b+R2d rule for
 * THIS repo (Desktop's commonMain diverges from Mobile's ~107 files, so its crossing set differs).
 * Ratchet: only shrinks. Do not add entries to silence a rule.
 */
object ArchBaseline {
    val crossings: Set<String> = setOf(
        "androidHostTest/kotlin/com/nuvio/app/features/player/PlayerTargetBufferBytesTest.kt",
        "androidMain/kotlin/com/nuvio/app/MainActivity.kt",
        "androidMain/kotlin/com/nuvio/app/NuvioApplication.kt",
        "androidMain/kotlin/com/nuvio/app/core/ui/PlatformImageLoader.android.kt",
        "androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt",
        "desktopMain/kotlin/com/nuvio/app/core/ui/PlatformImageLoader.desktop.kt",
        "iosMain/kotlin/com/nuvio/app/core/ui/PlatformImageLoader.ios.kt",
        "iosMain/kotlin/com/nuvio/app/features/player/PlayerEngine.ios.kt",
    )
}
