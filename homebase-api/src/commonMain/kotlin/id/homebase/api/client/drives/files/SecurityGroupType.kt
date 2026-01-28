package id.homebase.api.client.drives.files

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Security group types for access control. Ported from TypeScript SecurityGroupType enum. */
@Serializable
enum class SecurityGroupType(val value: String) {
    @SerialName("anonymous") Anonymous("anonymous"),
    @SerialName("authenticated") Authenticated("authenticated"),
    @SerialName("connected") Connected("connected"),
    @SerialName("autoconnected") AutoConnected("autoconnected"),
    @SerialName("owner") Owner("owner");

    companion object {
        fun fromString(value: String): SecurityGroupType {
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: Anonymous
        }
    }
}
