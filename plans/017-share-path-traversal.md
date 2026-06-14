# Plan 017: Sanitize externally-supplied share filenames against path traversal

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-common/src/commonMain/kotlin/id/homebase/core/share/ShareContentProcessor.kt homebase-common/src/commonMain/kotlin/id/homebase/core/share/SharedContentDescriptor.kt homebase-common/src/commonMain/kotlin/id/homebase/core/share/ShareCacheStorage.kt homebase-common/src/commonTest/kotlin/id/homebase/core/share/`. If any in-scope file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P3
- Effort: S
- Risk: LOW
- Depends on: none
- Category: security
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
`ShareContentProcessor.resolveFilePath(fileName)` builds an on-disk path by string-concatenating the shared-files directory with the **raw** `fileName`, with no sanitization (`"${cacheStorage.getSharedFilesDirectory()}/$fileName"`). That `fileName` is not trusted app data: it comes from `SharedContentDescriptor.fileNames`, which is JSON that is round-tripped through `cacheStorage.readSharedContent()` / `decodeFromString` (`readPendingContent`, lines 20-28). On iOS the descriptor is written by a **separate process** (the share extension) into the App Group container and read back by the main app; on Android it is constructed in-process from an inbound `ACTION_SEND` intent. A crafted share payload, a malicious/buggy source app, or a tampered on-disk `shared_content.json` can supply a `fileName` like `../logs/homebase.log` or `..\\..\\secure_storage`. The resolved path then escapes the shared-files directory.

Two consumers feed that path straight into attachment builders that read the file and upload its bytes:
- Android in-process send: `homebase-chat/.../MessageActionsHandler.kt:947` builds `AttachmentInput(filePath = resolveFilePath(name), …)` for `MessageAttachmentBuilder.build`.
- iOS moment handoff: `homebase-core/.../AppViewModel.kt:226` calls `sharedMediaAttachment(resolveFilePath(name), mime)`.

The blast radius is sandbox-bounded (the process can only reach paths its own UID can read/write), but within that sandbox an attacker-influenced traversal can read app-private files that should never leave the device — the Kermit log file (`files/logs/homebase.log`, which contains diagnostic data), or the Desktop `secure_storage` file — and exfiltrate them as a "shared" attachment, or clobber them on the write/cleanup side. The fix is a small, pure, well-tested basename-and-reject helper at the single resolve choke point, plus a defense-in-depth assertion that the resolved path stays under the shared-files directory. No API surface change, no new dependency.

## Current state

### File 1 — the only production file to modify
`homebase-common/src/commonMain/kotlin/id/homebase/core/share/ShareContentProcessor.kt`

The unsanitized resolve (lines 30-35):
```kotlin
// lines 30-35
    /**
     * Resolve a shared file name to its full path in the shared files directory.
     */
    fun resolveFilePath(fileName: String): String {
        return "${cacheStorage.getSharedFilesDirectory()}/$fileName"
    }
```
The class header and untrusted source of the name (lines 11-28):
```kotlin
// lines 11-28
class ShareContentProcessor(
    private val cacheStorage: ShareCacheStorage,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Read the pending shared content descriptor.
     * Returns null if no shared content is waiting.
     */
    fun readPendingContent(): SharedContentDescriptor? {
        return try {
            val raw = cacheStorage.readSharedContent() ?: return null
            json.decodeFromString<SharedContentDescriptor>(raw)
        } catch (e: Exception) {
            Logger.e(tag = "ShareContentProcessor") { "Failed to read shared content: ${e.message}" }
            null
        }
    }
```
Imports currently present at top of file (lines 1-4): `package id.homebase.core.share`, `import co.touchlab.kermit.Logger`, `import kotlinx.serialization.json.Json`. No other imports.

### The untrusted-name model
`homebase-common/src/commonMain/kotlin/id/homebase/core/share/SharedContentDescriptor.kt` (lines 11-19)
```kotlin
@Serializable
data class SharedContentDescriptor(
    val contentType: SharedContentType,
    val text: String? = null,
    val url: String? = null,
    val fileNames: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val targetConversationId: String,
)
```
`fileNames` is a plain `List<String>` with no validation. (Do NOT modify this file — see Out of scope.)

### The two consumers of `resolveFilePath` (read-only — verify, do not change)
- `homebase-chat/src/commonMain/kotlin/id/homebase/chat/conversationlist/MessageActionsHandler.kt:947`
  ```kotlin
  // lines 945-953 (context)
  val attachments =
      descriptor.fileNames.zip(descriptor.mimeTypes).map { (name, mime) ->
          val filePath = shareContentProcessor.resolveFilePath(name)
          AttachmentInput(
              filePath = filePath,
              contentType = mime,
              displayName = name,
          )
      }
  ```
- `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/AppViewModel.kt:226`
  ```kotlin
  // lines 225-227 (context)
  val attachments = descriptor.fileNames.zip(descriptor.mimeTypes).map { (name, mime) ->
      sharedMediaAttachment(shareContentProcessor.resolveFilePath(name), mime)
  }
  ```
  Note: `displayName = name` (Android consumer) and the `name` passed to `sharedMediaAttachment` keep the original — that is correct; we only sanitize the **filesystem path**, not the human-readable display name. No consumer change is required because the fix lives entirely inside `resolveFilePath`.

### Platform `getSharedFilesDirectory()` implementations (read-only — context for the prefix assertion)
All actuals return an **absolute** directory path:
- `homebase-common/src/jvmMain/.../ShareCacheStorage.jvm.kt:46-48` → `File(cacheDir, "shared_files").also { it.mkdirs() }.absolutePath` (e.g. `~/.homebase/share/shared_files`).
- `homebase-common/src/androidMain/.../ShareCacheStorage.android.kt:45` → absolute internal-files path.
- `homebase-common/src/nativeMain/.../ShareCacheStorage.native.kt:84` → absolute App Group path.
- `homebase-common/src/wasmJsMain/.../ShareCacheStorage.web.kt:29` → returns `""` (web has no share; resolve is never reached there).

### Testability fact (drives the test design)
`ShareCacheStorage` is an `expect class` (`ShareCacheStorage.kt:9`) with **no fake/mock** in any test source set, and `ShareContentProcessor` takes it as a concrete constructor parameter (not an interface). Therefore a pure `commonTest` cannot cheaply construct a `ShareContentProcessor` without dragging in a real filesystem-backed actual. The plan extracts the sanitization into a **pure, free `internal` function** (`sanitizeSharedFileName`) that the test calls directly, with no `ShareCacheStorage` instance needed. `commonTest` shares the module with `commonMain`, so it can see `internal` declarations. Exemplar of a pure-function `commonTest` in this module's util package: `homebase-common/src/jvmTest/.../util/ByteArrayExtensionsTest.kt` (and `TrimToUtf8BoundaryTest` therein).

### Test layout exemplars
- A `commonTest` lives at `homebase-common/src/commonTest/kotlin/id/homebase/core/...`. There is currently **no** `.../core/share/` directory under `commonTest` — you will create it.
- Pure-logic assertion style (`kotlin.test.Test`, `assertEquals`, `assertTrue`, `assertFailsWith`): see `homebase-common/src/jvmTest/kotlin/id/homebase/core/util/ByteArrayExtensionsTest.kt`.

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (run first) | `git diff --stat 45e2832e..HEAD -- homebase-common/src/commonMain/kotlin/id/homebase/core/share/ShareContentProcessor.kt homebase-common/src/commonMain/kotlin/id/homebase/core/share/SharedContentDescriptor.kt homebase-common/src/commonMain/kotlin/id/homebase/core/share/ShareCacheStorage.kt homebase-common/src/commonTest/kotlin/id/homebase/core/share/` | No output, OR only changes consistent with the excerpts above |
| Confirm finding line still matches | `grep -n 'getSharedFilesDirectory()}/\$fileName' homebase-common/src/commonMain/kotlin/id/homebase/core/share/ShareContentProcessor.kt` | Prints the line currently at 34 |
| Compile common (JVM target) — primary gate | `./gradlew :homebase-common:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Run the new + existing common JVM tests | `./gradlew :homebase-common:jvmTest` | `BUILD SUCCESSFUL`; new `ShareContentProcessorTest` passes |
| Compile consumers unchanged (chat) | `./gradlew :homebase-chat:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile consumers unchanged (core) | `./gradlew :homebase-core:compileKotlinJvm` | `BUILD SUCCESSFUL` |

## Scope
**In scope (modify / create only these):**
- `homebase-common/src/commonMain/kotlin/id/homebase/core/share/ShareContentProcessor.kt` — add `sanitizeSharedFileName` helper + a prefix-containment check; route `resolveFilePath` through both.
- `homebase-common/src/commonTest/kotlin/id/homebase/core/share/ShareContentProcessorTest.kt` — **new** test file for the pure helper.
- `plans/README.md` — update the plan row when done (create the row if the file/row does not exist).

**Out of scope (do NOT touch):**
- `SharedContentDescriptor.kt` — the data model is fine; validate at the resolve choke point, not by changing the wire type (keeps backward-compat with already-staged descriptors).
- `ShareCacheStorage.kt` and all platform actuals (`*.jvm.kt`, `*.android.kt`, `*.native.kt`, `*.web.kt`) — the write/cleanup side is not the attack surface this plan addresses; validating on read/resolve is sufficient and avoids platform fan-out.
- `ShareCacheStorage` write path / share extension (iOS Swift, Android share activity) — the spec scopes this to validate on read/resolve only.
- `MessageActionsHandler.kt` and `AppViewModel.kt` — consumers need no change; the fix is contained in `resolveFilePath`. (Only verify they still compile.)
- `ShareConversationCacheWriter.kt`, `ShareableConversation.kt`, `ShareCacheStorage` `writeGroupAvatar` — unrelated to `fileNames`.
- `targetConversationId` sanitization — a separate concern (it is not used to build a filesystem path in the resolve flow); note it in Maintenance, do not address here.

## Steps

1. **Drift check.** Run the Drift-check command and the "Confirm finding line" grep. If `ShareContentProcessor.kt` no longer matches the excerpt (e.g. already sanitized, or `resolveFilePath` moved/renamed), STOP and report — the finding may already be fixed.
   Verify: `grep -n 'getSharedFilesDirectory()}/\$fileName' homebase-common/src/commonMain/kotlin/id/homebase/core/share/ShareContentProcessor.kt` → prints exactly one line (the body of `resolveFilePath`).

2. **Add the pure sanitizer and route `resolveFilePath` through it.** Edit `ShareContentProcessor.kt`. Replace the existing `resolveFilePath` (lines 30-35) with the version below, and add the two free functions **below the class** (top-level, same file, same package — so `commonTest` can call them without a `ShareCacheStorage`). Do not add any new import; the code below uses only stdlib.

   Replace:
   ```kotlin
    /**
     * Resolve a shared file name to its full path in the shared files directory.
     */
    fun resolveFilePath(fileName: String): String {
        return "${cacheStorage.getSharedFilesDirectory()}/$fileName"
    }
   ```
   with:
   ```kotlin
    /**
     * Resolve a shared file name to its full path in the shared files directory.
     *
     * [fileName] originates from an untrusted [SharedContentDescriptor] (the iOS share
     * extension or an inbound Android share intent), so it is reduced to a bare basename
     * and rejected if it could escape the shared-files directory. See [sanitizeSharedFileName].
     *
     * @throws IllegalArgumentException if [fileName] is not a safe basename, or if the
     *   resolved path would fall outside the shared-files directory.
     */
    fun resolveFilePath(fileName: String): String {
        val baseDir = cacheStorage.getSharedFilesDirectory()
        val safeName = sanitizeSharedFileName(fileName)
        val resolved = "$baseDir/$safeName"
        // Defense in depth: the basename rules already forbid separators, but assert the
        // joined path still sits under the (absolute) base directory. baseDir is "" only on
        // web, where resolve is never reached; guard against the empty-prefix degenerate case.
        require(baseDir.isNotEmpty() && resolved.startsWith("$baseDir/")) {
            "Resolved shared path escaped the shared-files directory"
        }
        return resolved
    }
   ```
   Then add, **after** the closing brace of the `ShareContentProcessor` class (end of file):
   ```kotlin

   /**
    * Reduce an untrusted shared file name to a safe basename, or throw.
    *
    * Accepts only a plain file name with no path component. Rejects any value that, after
    * taking the substring past the last forward- or back-slash, is empty, ".", "..", or
    * still contains a separator (defensive — should be impossible after the basename step).
    * This blocks "../x", "a/b", "..\\x", absolute paths, and lone "." / ".." traversal tokens.
    *
    * @throws IllegalArgumentException if [fileName] cannot be reduced to a safe basename.
    */
   internal fun sanitizeSharedFileName(fileName: String): String {
       // Take everything after the last '/' or '\' — i.e. the basename.
       val lastSeparator = maxOf(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'))
       val base = if (lastSeparator >= 0) fileName.substring(lastSeparator + 1) else fileName
       require(
           base.isNotEmpty() &&
               base != "." &&
               base != ".." &&
               !base.contains('/') &&
               !base.contains('\\')
       ) {
           "Unsafe shared file name: '$fileName'"
       }
       return base
   }
   ```
   Verify: `./gradlew :homebase-common:compileKotlinJvm` → `BUILD SUCCESSFUL`.

3. **Create the test file.** Create `homebase-common/src/commonTest/kotlin/id/homebase/core/share/ShareContentProcessorTest.kt` with the content in the Test plan section below.
   Verify: `ls homebase-common/src/commonTest/kotlin/id/homebase/core/share/ShareContentProcessorTest.kt` → file exists.

4. **Run the common JVM test suite.**
   Verify: `./gradlew :homebase-common:jvmTest` → `BUILD SUCCESSFUL`; the report lists `ShareContentProcessorTest` with all cases green. (If a STOP condition's "test infra missing" appears, see STOP conditions.)

5. **Confirm consumers still compile unchanged.**
   Verify: `./gradlew :homebase-chat:compileKotlinJvm :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL` for both.

6. **Update the plan ledger.** In `plans/README.md`, mark this plan's row Done (status / date / verifying commands run). If `plans/README.md` does not yet exist or has no row for 017, add a one-line row: `| 017 | Sanitize share filenames against path traversal | security | P3 | Done |` consistent with the table format already used by neighboring rows (match whatever columns exist; if the file is absent, create a minimal table with a header and this row).
   Verify: `grep -n '017' plans/README.md` → prints the row.

## Test plan
**New file:** `homebase-common/src/commonTest/kotlin/id/homebase/core/share/ShareContentProcessorTest.kt`

Tests the pure `sanitizeSharedFileName` directly (no `ShareCacheStorage` needed). It is `internal` and in the same module, so `commonTest` can call it. Model after `ByteArrayExtensionsTest` (plain `kotlin.test`).

Cases (each is the regression this fix prevents):
- `parentDirectoryTraversal_rejected` — `"../logs/homebase.log"` → `assertFailsWith<IllegalArgumentException>`. (This is the core exploit: escaping to the Kermit log file.)
- `backslashTraversal_rejected` — `"..\\..\\secure_storage"` → `assertFailsWith<IllegalArgumentException>`. (Windows-style separators; covers the Desktop secure-storage clobber.)
- `subdirectoryName_reducedToBasename` — `"sub/photo.jpg"` → returns `"photo.jpg"` (no separator survives; stays in the shared dir).
- `absolutePath_reducedToBasename` — `"/etc/passwd"` → returns `"passwd"`.
- `loneDot_rejected` — `"."` → `assertFailsWith<IllegalArgumentException>`.
- `loneDotDot_rejected` — `".."` → `assertFailsWith<IllegalArgumentException>`.
- `emptyName_rejected` — `""` → `assertFailsWith<IllegalArgumentException>`.
- `trailingSlash_rejected` — `"foo/"` → basename is empty → `assertFailsWith<IllegalArgumentException>`.
- `normalFileName_passesThrough` — `"photo.jpg"` → returns `"photo.jpg"` unchanged (the happy path must still work).
- `dottedButValidName_passesThrough` — `"my.report.v2.pdf"` → returns unchanged (only exact "." / ".." are rejected, not names that merely contain dots).

```kotlin
package id.homebase.core.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShareContentProcessorTest {

    @Test
    fun parentDirectoryTraversal_rejected() {
        assertFailsWith<IllegalArgumentException> {
            sanitizeSharedFileName("../logs/homebase.log")
        }
    }

    @Test
    fun backslashTraversal_rejected() {
        assertFailsWith<IllegalArgumentException> {
            sanitizeSharedFileName("..\\..\\secure_storage")
        }
    }

    @Test
    fun subdirectoryName_reducedToBasename() {
        assertEquals("photo.jpg", sanitizeSharedFileName("sub/photo.jpg"))
    }

    @Test
    fun absolutePath_reducedToBasename() {
        assertEquals("passwd", sanitizeSharedFileName("/etc/passwd"))
    }

    @Test
    fun loneDot_rejected() {
        assertFailsWith<IllegalArgumentException> { sanitizeSharedFileName(".") }
    }

    @Test
    fun loneDotDot_rejected() {
        assertFailsWith<IllegalArgumentException> { sanitizeSharedFileName("..") }
    }

    @Test
    fun emptyName_rejected() {
        assertFailsWith<IllegalArgumentException> { sanitizeSharedFileName("") }
    }

    @Test
    fun trailingSlash_rejected() {
        assertFailsWith<IllegalArgumentException> { sanitizeSharedFileName("foo/") }
    }

    @Test
    fun normalFileName_passesThrough() {
        assertEquals("photo.jpg", sanitizeSharedFileName("photo.jpg"))
    }

    @Test
    fun dottedButValidName_passesThrough() {
        assertEquals("my.report.v2.pdf", sanitizeSharedFileName("my.report.v2.pdf"))
    }
}
```

Verify command for the whole test plan: `./gradlew :homebase-common:compileKotlinJvm` then `./gradlew :homebase-common:jvmTest` → both `BUILD SUCCESSFUL`.

## Done criteria
- [ ] `git diff --stat` shows changes ONLY in `ShareContentProcessor.kt`, the new `ShareContentProcessorTest.kt`, and `plans/README.md`.
- [ ] `grep -n 'getSharedFilesDirectory()}/\$fileName' homebase-common/src/commonMain/kotlin/id/homebase/core/share/ShareContentProcessor.kt` → **no match** (the raw concatenation is gone).
- [ ] `grep -n 'sanitizeSharedFileName' homebase-common/src/commonMain/kotlin/id/homebase/core/share/ShareContentProcessor.kt` → matches in both `resolveFilePath` and the helper definition.
- [ ] `./gradlew :homebase-common:compileKotlinJvm` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-common:jvmTest` → `BUILD SUCCESSFUL` with `ShareContentProcessorTest` all green.
- [ ] `./gradlew :homebase-chat:compileKotlinJvm :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL` (consumers untouched).
- [ ] No new `import` added to `ShareContentProcessor.kt` (helper uses stdlib only).
- [ ] `plans/README.md` row for 017 updated/added.

## STOP conditions
- Drift check fails or the finding grep (step 1) does not match → STOP; the resolve may already be sanitized or relocated.
- `resolveFilePath` has gained additional callers that pass a pre-joined absolute path (not a bare name) → STOP and reassess; the basename reduction assumes callers pass a single shared file **name**, which is true for both current consumers (`descriptor.fileNames` entries).
- `:homebase-common:jvmTest` fails to find/run tests because the module's test infra changed (e.g. `commonTest` no longer compiled into the JVM test source set) → STOP and report; do not "fix" by moving the test to a platform-specific source set without confirming why.
- Any consumer (`MessageActionsHandler.kt`, `AppViewModel.kt`) now relies on `resolveFilePath` NOT throwing (e.g. wraps it expecting a null/empty return) → STOP; the new `IllegalArgumentException` would surface there. (As written, both call it inside a `try`/`map` that already tolerates failure — verify before proceeding.)

## Maintenance notes
- **Reviewer scrutiny:** confirm the basename rule is the *only* place a `fileName` becomes a path. If a future feature reads `descriptor.fileNames` and joins a path **without** going through `resolveFilePath`, it bypasses this guard — grep for `getSharedFilesDirectory()` and `.fileNames` on any new code. The two existing joiners both route through `resolveFilePath`.
- **Why throw rather than skip:** throwing on a malicious name fails the whole share loudly instead of silently dropping one attachment; both consumers iterate with `.map` inside a `try`, so a throw aborts that share attempt (acceptable — a traversal payload is not a legitimate share). If product wants graceful per-file skipping later, change the consumers to filter, not the sanitizer to return null.
- **`targetConversationId` (deferred):** it is also untrusted descriptor data but is currently compared as a string / parsed as a conversation id, not used to build a filesystem path in this flow. Out of scope here; worth a follow-up audit if it ever reaches a path or a SQL fragment.
- **iOS write side (deferred):** this plan validates on read/resolve. The iOS share extension still writes attacker-influenced names into the App Group on the write path; validating there too (belt-and-suspenders) is a reasonable follow-up but is explicitly out of this plan's scope.
- **Platform note:** `getSharedFilesDirectory()` returns `""` on web (`ShareCacheStorage.web.kt:29`); the `require(baseDir.isNotEmpty() …)` guard makes a stray web call fail fast rather than produce a root-relative path. Web never reaches the share-resolve flow today.
