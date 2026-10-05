package com.nuvio.app.core.contracts

/**
 * Port (F17): the public IMDb-based id an IPTV movie/episode can be looked up under by subtitle
 * add-ons (`tt…` or `tt…:S:E`), so OpenSubtitles works inside the IPTV section without the player
 * naming features/iptv. Null = unknown; the player then asks no add-on at all (provider-scoped ids
 * carry credentials — see AddonSubtitleIdPolicy).
 */
interface IptvSubtitleIdResolver {
    suspend fun publicSubtitleVideoId(parentMetaId: String, season: Int?, episode: Int?): String?
}

/** No IPTV feature wired (tests, previews, IPTV-free builds) → no public id. */
private object NoOpIptvSubtitleIdResolver : IptvSubtitleIdResolver {
    override suspend fun publicSubtitleVideoId(parentMetaId: String, season: Int?, episode: Int?): String? = null
}

object IptvSubtitleIdAccess {
    private var instance: IptvSubtitleIdResolver = NoOpIptvSubtitleIdResolver
    val resolver: IptvSubtitleIdResolver get() = instance
    fun register(resolver: IptvSubtitleIdResolver) { instance = resolver }
}
