package id.homebase.api.client

import io.ktor.http.Headers

data class ApiResponse(
    val status: Int,
    val headers: Headers,
    val body: String
)
