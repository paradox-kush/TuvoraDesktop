package com.nuvio.app.features.party

/** Whether this member may change what the party is watching to [media]. Pure. */
internal object PartyMediaChangePolicy {
    fun canPublish(party: ActiveParty, media: PartyMedia): Boolean =
        media != party.media && party.isHost
}
