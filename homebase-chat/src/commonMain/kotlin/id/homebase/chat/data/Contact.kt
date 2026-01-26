package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import kotlin.uuid.Uuid

@Immutable
data class Contact(
    val id: Uuid,
    val name: String,
    val avatarInitials: String,
    val avatarUrl: String = "",
    val status: String = "Available"
)
