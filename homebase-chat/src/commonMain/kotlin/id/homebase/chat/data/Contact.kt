package id.homebase.chat.data

import androidx.compose.runtime.Immutable

@Immutable
data class Contact(
    val id: String,
    val name: String,
    val avatarInitials: String,
    val avatarUrl: String = "",
    val status: String = "Available"
)
