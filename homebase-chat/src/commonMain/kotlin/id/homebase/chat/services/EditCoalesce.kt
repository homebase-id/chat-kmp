package id.homebase.chat.services

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest

/**
 * Rebuild a still-pending create so it carries an edit's new content while
 * preserving the original message's media payloads.
 *
 * Why this exists: editing a message whose create hasn't reached the server
 * must keep it a *create* — downgrading to an `UpdateFile` would strand it
 * ("Could not find file with uniqueId"); see
 * `OutboxSync.wouldStrandPendingCreate`. But a create is a *full snapshot*
 * (unlike an update, which is a delta that leaves untouched payloads alone), so
 * we must carry forward the original media payloads. We swap only:
 *
 *  - the header [encryptedMetadata] (the edited text), and
 *  - the text-overflow payload (`ChatProtocol.DefaultPayloadKey`) — replaced by
 *    [encryptedOverflowPayloads] (empty when the edited text fits in the header).
 *
 * Everything else on the queued create — `transitOptions` (recipients +
 * notification), `thumbnails`, `keyHeader`, media payloads — is preserved via
 * `copy`. Pure + unit-testable (the encryption is done by the caller).
 */
internal fun amendPendingCreate(
    existingCreate: UploadFileRequest,
    encryptedMetadata: UploadFileMetadata,
    encryptedOverflowPayloads: List<PayloadFile>,
): UploadFileRequest =
    existingCreate.copy(
        metadata = encryptedMetadata,
        payloads = existingCreate.payloads.filterNot { it.key == ChatProtocol.DefaultPayloadKey } +
            encryptedOverflowPayloads,
    )
