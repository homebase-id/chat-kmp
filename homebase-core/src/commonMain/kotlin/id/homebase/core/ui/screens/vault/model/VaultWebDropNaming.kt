package id.homebase.core.ui.screens.vault.model

/**
 * Names for a Vault entry's pages when they leave as one WebDrop: a single payload keeps the
 * entry's name (`Passport.jpg`), a bundle numbers its pages 1-based in page order
 * (`Insurance card-1.png`, `-2.png`, ...). The recipient sees these names in the drop manifest
 * and as download filenames, so they must look like files, not like `vlt_pg_00`.
 */
internal fun VaultEntry.webDropFileNames(): List<String> =
    webDropFileNamesFor(fileName, payloadDescriptors.map { it.contentType ?: contentType })

/**
 * Extension rule byte-identical to [VaultUploaderService.downloadPayload]'s temp-file suffix
 * (`substringAfter("/", "bin")`, `jpeg` -> `jpg`) so the PickedDropFile name always agrees with
 * the materialized file's extension.
 */
internal fun webDropExtensionFor(contentType: String?): String =
    (contentType ?: "").substringAfter("/", "bin").let { if (it == "jpeg") "jpg" else it }
        .ifEmpty { "bin" }

internal fun webDropFileNamesFor(entryName: String, contentTypes: List<String?>): List<String> {
    val total = contentTypes.size
    return contentTypes.mapIndexed { index, contentType ->
        val ext = webDropExtensionFor(contentType)
        // Strip a MATCHING extension case-insensitively so "scan.pdf" doesn't become
        // "scan.pdf-1.pdf" - but never blind-substringBeforeLast: a dotted name with no real
        // extension ("v2.final") must survive untouched.
        val base = if (entryName.length > ext.length + 1 &&
            entryName.endsWith(".$ext", ignoreCase = true)
        ) {
            entryName.dropLast(ext.length + 1)
        } else {
            entryName
        }
        if (total == 1) "$base.$ext" else "$base-${index + 1}.$ext"
    }
}

/**
 * Synthetic [VaultUiState.preparingShareKeys] key for the entry-scoped WebDrop preparation:
 * page keys guard per-page share/save; this guards (and spins) the whole entry.
 */
internal fun webDropGuardKey(file: VaultEntry): String = "webdrop:${file.uniqueId}"
