package id.homebase.api.sync.database

import id.homebase.api.client.ClientException
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.upload.StagedPayloadMissingException

/**
 * The single classification point for outbox upload failures: returns a
 * human-readable reason when [e] describes a state that won't be fixed by
 * retrying (file not found server-side, missing version tag for an update,
 * etc.), or null when the failure is retryable. A non-null result makes
 * [OutboxSync] drop the row immediately instead of burning ~48h of
 * exponential-backoff retries, and the reason rides on the
 * `OutboxItemDropped` event and the DROPPING log line.
 *
 * Pure + unit-testable (see OutboxFailureClassifierTest). Every uploader-side
 * terminal condition belongs HERE — uploaders must throw, never swallow a
 * terminal error by returning normally (that made OutboxSync emit
 * ItemCompleted for an upload that never reached the server).
 */
internal fun classifyPermanentFailure(e: Throwable): String? {
    if (e is NotFoundException) return "404 NotFound"
    // A staged payload file is gone at drain time (#842). The source bytes no
    // longer exist locally, so no retry can succeed — drop on attempt 1 instead
    // of burning ~48h as a phantom "Network failure" (the pre-#842 symptom when
    // the sweeper/OS reclaimed an enc temp out from under a pending row). Thrown
    // by DriveUploadProvider's pre-flight BEFORE the network call, so it can't
    // be wrapped into a transient NetworkException.
    if (e is StagedPayloadMissingException) return "staged payload missing: ${e.message}"
    if (e is ClientException) {
        when (e.errorCode) {
            OdinClientErrorCode.FileNotFound,
            OdinClientErrorCode.MissingVersionTag,
            OdinClientErrorCode.VersionTagMismatch,
            OdinClientErrorCode.CannotOverwriteNonExistentFile,
            OdinClientErrorCode.MustRotateKeyHeaderIvWhenUpdating,
            OdinClientErrorCode.UnknownId -> return reasonOf(e)
            else -> Unit
        }
        // The server sometimes returns 400 with the structured errorCode
        // collapsed to UnhandledScenario but the message text intact.
        // Catch the recurring local-only-placeholder failures we've seen
        // so they don't loop in the outbox. The version-tag check matches
        // both "Missing version tag" and "Mismatching version tag".
        val msg = e.message ?: return null
        if (msg.contains("Could not find file", ignoreCase = true)) return reasonOf(e)
        if (msg.contains(Regex("Mis(sing|matching) version tag", RegexOption.IGNORE_CASE))) return reasonOf(e)
        // Server-enforced size invariants — never recover with retry.
        // Catches "Thumbnail size of N exceeds 1024" (the bug behind
        // the URL-preview SVG outbox stall on 2026-05-17) and any
        // sibling quota messages the server emits in the same shape
        // with errorCode collapsed to UnhandledScenario.
        if (msg.contains(Regex("size of \\d+ exceeds \\d+", RegexOption.IGNORE_CASE))) return reasonOf(e)
        // Encrypted-file key mismatch on update: the server rejects an update
        // whose AES key differs from the existing file's ("When updating an
        // encrypted file, the AES key must match the existing key …"). The
        // outbox row carries a fixed key, so every retry replays the same
        // wrong key against an unchanging server file — deterministically
        // unrecoverable. Drop it instead of burning ~48h of retries.
        // The root cause (DriveOutboxUploader.retryAsUpdate reusing the
        // client's diverged keyHeader on ExistingFileWithUniqueId) is fixed
        // for header-only requests by `rekeyedUpdateForExistingServerFile`,
        // which re-encrypts with the server's key. This remains the guardrail
        // for payload-carrying requests, whose pre-encrypted bytes can't be
        // safely re-keyed in memory — those flows self-heal (location
        // re-flushes its buffer on OutboxItemDropped, chat offers Retry).
        if (msg.contains("AES key must match", ignoreCase = true)) return reasonOf(e)
        // Encrypted-file payload IV reuse on update: the server rejects an
        // update that re-sends a payload under its original IV ("When updating a
        // file, you must change the Iv", errorCode collapsed to
        // UnhandledScenario). The pre-encrypted bytes in the outbox row are
        // sealed under that IV and can't be safely re-keyed in memory, so every
        // retry replays the same IV — a deterministic ~48h loop (the
        // image-to-Leela stall, homebase.log 2026-06-13). The lost-ack case
        // (our own create already landed) is recovered upstream in
        // DriveOutboxUploader.retryAsUpdate via serverFileIsOurLandedCreate;
        // this is the guardrail for any residual path so it drops honestly
        // instead of looping (chat offers Retry).
        if (msg.contains("you must change the Iv", ignoreCase = true)) return reasonOf(e)
        // Client-side pre-flight rejections from
        // [UploadValidation.kt]. The validator throws ClientException
        // shaped like a server response so we land here on attempt 1.
        if (msg.startsWith("Upload validation failed: ")) return reasonOf(e)
        // Self-recipient: the outbox item's recipient list contains the
        // logged-in identity. The server will reject this forever. Title-match
        // because the server returns errorCode=UnhandledScenario for this
        // case; there's no dedicated enum value. (Previously swallowed inside
        // DriveOutboxUploader.upload by returning normally, which faked a
        // successful send — ItemCompleted for an upload the server 400'd.)
        if (msg.startsWith("Cannot transfer to yourself")) return reasonOf(e)
    }
    return null
}

private fun reasonOf(e: ClientException): String = "errorCode=${e.errorCode} msg=${e.message}"
