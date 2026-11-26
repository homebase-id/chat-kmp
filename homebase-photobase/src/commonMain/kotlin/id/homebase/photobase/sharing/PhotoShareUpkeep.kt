package id.homebase.photobase.sharing

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.shares.SelectiveShareUpkeep
import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Album shares kept current: keys sealed to the people who set up Photos since, Write taken from ended Add shares, and
 * the photos members added to shared albums given their local tag.
 */
class PhotoShareUpkeep(
    sharing: PhotoAlbumSharing,
    credentials: CredentialsManager,
    online: StateFlow<Boolean>,
    eventBus: EventBus,
    scope: CoroutineScope,
) : SelectiveShareUpkeep(
    tag = "PhotoShareUpkeep",
    session = PhotobaseSession,
    credentials = credentials,
    online = online,
    eventBus = eventBus,
    scope = scope,
    signOut = sharing::signOut,
    signOutIfRefused = sharing::signOutIfRefused,
    steps = listOf(
        "sealing keys" to sharing::sealPending,
        "bringing editors up to date" to { sharing.syncEditors() },
        "tagging members' photos" to sharing::bringSharedAlbumsTogether,
    ),
)
