package id.homebase.core.share

import kotlinx.serialization.Serializable

/**
 * Describes content shared from an external app into Homebase Chat.
 * On iOS, the share extension writes this to the App Group container;
 * the main app reads it to complete sending.
 * On Android, it's used in-process as a data model.
 */
@Serializable
data class SharedContentDescriptor(
    val contentType: SharedContentType,
    val text: String? = null,
    val url: String? = null,
    val fileNames: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val targetConversationId: String,
)

@Serializable
enum class SharedContentType {
    TEXT,
    URL,
    IMAGE,
    VIDEO,
    FILE,
    MIXED,
}
