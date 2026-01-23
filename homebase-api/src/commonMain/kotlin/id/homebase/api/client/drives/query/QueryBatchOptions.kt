package id.homebase.homebasekmppoc.prototype.lib.drives.query

import io.ktor.client.request.HttpRequestBuilder

/**
 * Options for queryBatch operation
 *
 * @param decrypt Whether to decrypt the response (default: true)
 * @param httpConfig Lambda to configure the HTTP request (equivalent to AxiosRequestConfig)
 */
data class QueryBatchOptions(
    val decrypt: Boolean = true,
    val httpConfig: (HttpRequestBuilder.() -> Unit)? = null
)
