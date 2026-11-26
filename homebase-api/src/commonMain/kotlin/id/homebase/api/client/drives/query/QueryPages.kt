package id.homebase.api.client.drives.query

import id.homebase.api.client.drives.ExternalFileKey
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchResponse
import id.homebase.api.common.OdinId
import kotlin.uuid.Uuid

/** Reads every page of [params] on [driveId] while [onPage] asks for the next; a cursor the server repeats ends it. */
suspend fun DriveQueryProvider.forEachPage(
    driveId: Uuid,
    params: FileQueryParams,
    options: QueryBatchResultOptionsRequest,
    ownerOdinId: OdinId? = null,
    externalFileKey: ExternalFileKey? = null,
    onPage: suspend (QueryBatchResponse) -> Boolean,
) {
    var cursor: String? = null
    do {
        val page = queryBatch(
            driveId,
            QueryBatchRequest(queryParams = params, resultOptionsRequest = options.copy(cursorState = cursor)),
            ownerOdinId = ownerOdinId,
            externalFileKey = externalFileKey,
        )
        val more = onPage(page)
        cursor = page.cursorState?.takeIf { more && page.hasMoreRows && it != cursor }
    } while (cursor != null)
}
