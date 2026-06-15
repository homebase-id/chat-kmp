@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** POST /api/v2/contacts body. No versionTag on create. */
@Serializable
data class CreateContactRequest(
    val content: ContactContent,
)

/** PUT /api/v2/contacts/{uniqueId} body. [versionTag] is required. */
@Serializable
data class UpdateContactRequest(
    val content: ContactContent,
    @Serializable(with = UuidSerializer::class) val versionTag: Uuid,
)
