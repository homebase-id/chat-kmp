package id.homebase.api.youauth

import id.homebase.api.encodeUrl


/**
 * OAuth authorization parameters for YouAuth flow. Matches the structure expected by the
 * /youauth/authorize endpoint.
 */
data class YouAuthorizationParams(
    val clientId: String,
    val clientType: ClientType = ClientType.domain,
    val clientInfo: String,
    val publicKey: String,
    val permissionRequest: String,
    val state: String,
    val redirectUri: String
) {
    /** Convert to URL query string for the authorization request. */
    fun toQueryString(): String {
        val params = mutableListOf<String>()
        if (clientId.isNotEmpty()) params.add("client_id=${encodeUrl(clientId)}")
        params.add("client_type=${encodeUrl(clientType.name)}")
        if (clientInfo.isNotEmpty()) params.add("client_info=${encodeUrl(clientInfo)}")
        if (publicKey.isNotEmpty()) params.add("public_key=${encodeUrl(publicKey)}")
        if (permissionRequest.isNotEmpty())
                params.add("permission_request=${encodeUrl(permissionRequest)}")
        if (state.isNotEmpty()) params.add("state=${encodeUrl(state)}")
        if (redirectUri.isNotEmpty()) params.add("redirect_uri=${encodeUrl(redirectUri)}")
        return params.joinToString("&")
    }

    /** Convert to a map for JSON serialization. */
    fun toMap(): Map<String, String> = buildMap {
        put("client_id", clientId)
        put("client_type", clientType.name)
        put("client_info", clientInfo)
        put("public_key", publicKey)
        put("permission_request", permissionRequest)
        put("state", state)
        put("redirect_uri", redirectUri)
    }
}
