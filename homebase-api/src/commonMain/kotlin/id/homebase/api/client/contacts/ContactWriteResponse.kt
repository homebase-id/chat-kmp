@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 200 OK body for CREATE and UPDATE. Keep [uniqueId] + [versionTag] to address/update the contact
 * later (the next UPDATE must send the latest [versionTag]).
 */
@Serializable
data class ContactWriteResponse(
    @Serializable(with = UuidSerializer::class) val uniqueId: Uuid,
    @Serializable(with = UuidSerializer::class) val versionTag: Uuid,
)
