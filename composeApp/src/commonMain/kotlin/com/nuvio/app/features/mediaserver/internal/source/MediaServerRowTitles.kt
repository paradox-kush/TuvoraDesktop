package com.nuvio.app.features.mediaserver.internal.source

import com.nuvio.app.features.mediaserver.api.MediaServerHomeRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.media_server_row_continue_watching
import nuvio.composeapp.generated.resources.media_server_row_next_up
import nuvio.composeapp.generated.resources.media_server_row_recently_added
import nuvio.composeapp.generated.resources.media_server_search_movies
import nuvio.composeapp.generated.resources.media_server_search_series
import org.jetbrains.compose.resources.getString

/** The localized titles of the rows a server contributes - behind a seam so row building is testable without resources. */
internal interface MediaServerRowTitles {
    suspend fun home(row: MediaServerHomeRow, serverName: String): String
    suspend fun searchMovies(serverName: String): String
    suspend fun searchSeries(serverName: String): String
}

internal object ResourceMediaServerRowTitles : MediaServerRowTitles {
    override suspend fun home(row: MediaServerHomeRow, serverName: String): String = when (row) {
        MediaServerHomeRow.CONTINUE_WATCHING -> getString(Res.string.media_server_row_continue_watching, serverName)
        MediaServerHomeRow.NEXT_UP -> getString(Res.string.media_server_row_next_up, serverName)
        MediaServerHomeRow.RECENTLY_ADDED -> getString(Res.string.media_server_row_recently_added, serverName)
    }

    override suspend fun searchMovies(serverName: String): String = getString(Res.string.media_server_search_movies, serverName)
    override suspend fun searchSeries(serverName: String): String = getString(Res.string.media_server_search_series, serverName)
}
