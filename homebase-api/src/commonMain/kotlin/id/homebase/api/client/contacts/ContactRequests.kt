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

/**
 * Body for both per-app app-data PUTs — the inline tier (`/app-data`) and the bulk tier
 * (`/app-ext-data`) share the identical shape. The server stamps the app's id from the auth token,
 * so [appId] is never part of the body.
 *
 * [content] is an **opaque** plaintext string sent over the normal shared-secret transport (the
 * server encrypts at rest — no client-side encryption on write). Structured data must be
 * JSON-serialized into this single string by the caller and parsed back on read.
 */
@Serializable
data class SetContactAppDataRequest(
    val content: String,
    @Serializable(with = UuidSerializer::class) val versionTag: Uuid,
)
