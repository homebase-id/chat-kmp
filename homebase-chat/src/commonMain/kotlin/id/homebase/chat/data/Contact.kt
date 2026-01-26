package id.homebase.chat.data

import androidx.compose.runtime.Immutable

@Immutable
data class Contact(
    val id: String,
    val name: String, //TODO: change to ContactName class?
    val avatarInitials: String,
    val avatarUrl: String = "",
    val status: String = "Available",
)
