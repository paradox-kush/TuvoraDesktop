package com.nuvio.app.core.contracts

/**
 * Spatial contract (Invariant S): a subsystem that must react when the active profile changes.
 *
 * A profile switch has to drop and reload cross-profile state (caches, recents, follows). The
 * shared [ProfileRepository][com.nuvio.app.features.profiles.ProfileRepository] owns the switch but
 * must not name fork features to do it — so fork subsystems register a participant here and the
 * switch fans out through the registry. Registration order IS the fan-out order (kept deterministic
 * by registering from the single FeatureWiring init).
 *
 * No-op default: with nothing registered (unit tests, previews) the fan-out is empty and the switch
 * still completes — pure profile logic never requires runtime wiring.
 */
fun interface ProfileChangeParticipant {
    fun onProfileChanged(profileIndex: Int)
}

object ProfileChangeParticipants {
    private val participants = mutableListOf<ProfileChangeParticipant>()

    fun register(participant: ProfileChangeParticipant) {
        participants += participant
    }

    fun all(): List<ProfileChangeParticipant> = participants.toList()

    /** Test-only: drop registrations so suites do not leak fork behaviour into each other. */
    fun resetForTest() {
        participants.clear()
    }
}
