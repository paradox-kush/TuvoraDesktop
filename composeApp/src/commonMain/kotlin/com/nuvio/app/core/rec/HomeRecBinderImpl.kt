package com.nuvio.app.core.rec

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import com.nuvio.app.core.contracts.HomeRecBinder
import com.nuvio.app.core.contracts.HomeRecItem

/**
 * Rec-backed [HomeRecBinder]: the home catalog's clicks and row impressions, logged to the HOME
 * surface. Registered by FeatureWiring so the shared home UI never imports core/rec. Behaviour is
 * identical to the inline calls this replaced — same surface, rowId, and RecImpressionItem mapping.
 */
internal object HomeRecBinderImpl : HomeRecBinder {
    override fun logRowClick(rowId: String, item: HomeRecItem, itemPosition: Int?) {
        recLogClick(
            surface = RecSurface.HOME,
            rowId = rowId,
            rowIndex = null,
            itemPosition = itemPosition,
            item = item.toRecImpressionItem(),
        )
    }

    override fun rowImpressions(
        rowId: String,
        itemAt: (Int) -> HomeRecItem?,
    ): (@Composable (LazyListState) -> Unit) = { listState ->
        RecRowImpressions(
            listState = listState,
            surface = RecSurface.HOME,
            rowId = rowId,
            rowIndex = null,
            itemAt = { index -> itemAt(index)?.toRecImpressionItem() },
        )
    }

    private fun HomeRecItem.toRecImpressionItem(): RecImpressionItem = RecImpressionItem(
        itemId = itemId,
        contentType = recContentTypeOf(contentType = rawContentType, season = null, episode = null),
    )
}
