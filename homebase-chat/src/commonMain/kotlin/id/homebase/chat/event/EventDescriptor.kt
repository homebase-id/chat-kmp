package id.homebase.chat.event

import kotlinx.serialization.Serializable

/**
 * Wire format for an Event message. Serialized as JSON into the chat message
 * header's `appData.content` field (alongside `appData.dataType =
 * ChatEventMessageDataType`), so it loads with the message index — no payload
 * fetch on scroll.
 *
 * All fields except [eventId], [title], [startUtcMs], [timezone],
 * [createdByOdinId], [createdAtUtcMs] are optional.
 */
@Serializable
data class EventDescriptor(
    val eventId: String,
    val title: String,
    val description: String = "",
    val startUtcMs: Long,
    val endUtcMs: Long? = null,
    val timezone: String,
    val locationText: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val meetingUrl: String? = null,
    val createdByOdinId: String,
    val createdAtUtcMs: Long,
    /**
     * Schema version for forward compatibility. Bump when the descriptor shape
     * changes in a way receivers need to know about. Receivers ignoring this
     * just see the new optional fields as default values.
     */
    val schemaVersion: Int = 1,
)
