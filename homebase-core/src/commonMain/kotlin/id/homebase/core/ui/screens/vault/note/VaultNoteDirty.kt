package id.homebase.core.ui.screens.vault.note

import id.homebase.core.util.stripComposerLineBreakArtifacts

internal fun isVaultNoteDirty(
    isCreateMode: Boolean,
    title: String,
    currentMarkdown: String,
    loadedTitle: String?,
    loadedMarkdown: String?,
): Boolean =
    if (isCreateMode) {
        title.isNotBlank() || currentMarkdown.isNotBlank()
    } else {
        title != (loadedTitle ?: "") ||
            currentMarkdown.stripComposerLineBreakArtifacts() !=
            (loadedMarkdown ?: "").stripComposerLineBreakArtifacts()
    }
