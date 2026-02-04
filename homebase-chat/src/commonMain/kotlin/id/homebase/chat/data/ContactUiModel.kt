package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import kotlin.uuid.Uuid

@Immutable
data class ContactUiModel(
    val id: Uuid,
    val odinId: String,
    val name: String, //TODO: change to ContactName class?
    val avatarInitials: String,
    val avatarUrl: String = "",
    val status: String = "Available",
)
