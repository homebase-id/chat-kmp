package id.homebase.core.lists

/**
 * Drive protocol constants for the Lists add-on. Lists files live on `listsLabeledDrive`
 * (see AppConfig). A list is a [ListDefinitionFileType] file whose uniqueId == groupId ==
 * listId (the conversation-file analog, fileType 8888); each item is a [ListItemFileType]
 * file whose uniqueId == itemId and groupId == listId. Both are header-only (descriptor JSON
 * in fileMetadata.appData.content) — no payloads — so they sit well under the 7 KB header cap.
 */
object ListsProtocol {
    const val ListDefinitionFileType = 9100
    const val ListItemFileType = 9101

    /** Code-point caps for user text (enforced at write time via truncateToCodePoints). */
    const val MaxTitleCodePoints = 80
    const val MaxItemBodyCodePoints = 2000
}
