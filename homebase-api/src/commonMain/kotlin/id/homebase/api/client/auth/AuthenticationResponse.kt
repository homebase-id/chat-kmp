package id.homebase.api.client.auth

import kotlinx.serialization.Serializable

@Serializable
class AuthenticationResponse (val sharedSecret: String)
