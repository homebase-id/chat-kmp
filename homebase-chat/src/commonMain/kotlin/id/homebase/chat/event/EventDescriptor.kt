package id.homebase.chat.event

import kotlinx.serialization.Serializable

/**
 * Wire format for an Event message. Serialized as JSON into the chat message
 * header's `appData.content` field (alongside `appData.dataType =
 * ChatEventMessageDataType`), so it loads with the message index — no payload
 * fetch on scroll.
 *
 * Identity / sender / creation time are NOT in the descriptor — read them
 * from the surrounding HomebaseFile envelope (`uniqueId`,
 * `fileMetadata.originalAuthor`, `userDate`). See the "Don't duplicate
 * envelope fields in the descriptor" rule under "Adding a New Typed Message
 * Kind" in `CLAUDE.md`.
 */
@Serializable
data class EventDescriptor(
    val title: String,
    val description: String = "",
    val startUtcMs: Long,
    val endUtcMs: Long? = null,
    val timezone: String,
    val locationText: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val meetingUrl: String? = null,
    /**
     * Schema version for forward compatibility. Bumped to 2 when the legacy
     * `eventId` / `createdByOdinId` / `createdAtUtcMs` fields were removed
     * (read those values from the message envelope instead). Older clients
     * that still require those fields in their data class will fail to
     * deserialize and render the "update the app" chip.
     */
    val schemaVersion: Int = 2,
)
