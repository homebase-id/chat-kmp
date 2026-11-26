package id.homebase.core.ui.screens.filesync

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.shares.SelectiveShareUpkeep
import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Filesync's shares kept current: keys sealed to the people who set up Filesync since, Write taken from ended Edit shares. */
class FilesyncShareUpkeep(
    sharing: FilesyncSharing,
    credentials: CredentialsManager,
    online: StateFlow<Boolean>,
    eventBus: EventBus,
    scope: CoroutineScope,
) : SelectiveShareUpkeep(
    tag = "FilesyncShareUpkeep",
    session = FilesyncSession,
    credentials = credentials,
    online = online,
    eventBus = eventBus,
    scope = scope,
    signOut = sharing::signOut,
    signOutIfRefused = sharing::signOutIfRefused,
    steps = listOf(
        "sealing keys" to sharing::sealPending,
        "bringing editors up to date" to { sharing.syncEditors() },
    ),
)
