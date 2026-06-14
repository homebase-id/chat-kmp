# Plan 016: Replace the hardcoded Desktop SecureStorage keystore password with a per-install machine-derived password (with migration)

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-api/src/jvmMain/kotlin/id/homebase/api/storage/SecureStorage.jvm.kt homebase-api/src/jvmMain/kotlin/id/homebase/api/file/JvmFileSystemUtil.kt homebase-api/build.gradle.kts`. On mismatch with the Current state excerpts below, STOP.

## Status
Priority **P3**; Effort **M**; Risk **MED**; Depends on: none; Category **security**; Planned at: commit 45e2832e, 2026-06-14.

Drift note: At commit 45e2832e the cited code matches the finding exactly — `codeMatchedFinding=true`. No drift. `KEYSTORE_PASSWORD` is the hardcoded constant at `SecureStorage.jvm.kt:24-25`; it is consumed at lines 43, 46, 54, 62, 68.

## Why this matters
On Desktop (JVM), `SecureStorage` is the root of the local trust chain: it encrypts the values that protect the **shared secret** (`CredentialStorage`) and the **database encryption key** (`DatabaseKeyManager` / SQLCipher). The AES-256 key that does that encryption lives in a PKCS12 keystore whose password is a **compile-time string constant** baked into the shipped binary (`SecureStorage.jvm.kt:24-25`, comment literally says "In production, derive from machine-specific data"). Anyone who can read the user's app-data directory — a stolen laptop, a backup, malware running as the user, a shared/synced home dir — can extract that constant from any copy of the binary and decrypt `secure_storage.p12` → the AES key → `secure_storage.properties` → the shared secret and DB key. The constant is identical on every install, so one extraction breaks all of them. Treat the committed value as **burned/rotated**. Replacing it with a password derived per-install from machine/user material (stored in an OS-protected sidecar) means an attacker needs both the data dir **and** that machine's sidecar, and breaking one install does not break others. A migration path re-keys existing keystores in place so no user loses stored credentials.

## Current state

### `homebase-api/src/jvmMain/kotlin/id/homebase/api/storage/SecureStorage.jvm.kt` (the file to change)
The JVM `actual object SecureStorage`. Relevant excerpts (verified at 45e2832e):

```kotlin
20  actual object SecureStorage {
21      private const val KEYSTORE_FILE = "secure_storage.p12"
22      private const val DATA_FILE = "secure_storage.properties"
23      private const val KEY_ALIAS = "SecureStorageKey"
24      private const val KEYSTORE_PASSWORD =
25          "SecureStorageKeyStorePassword" // In production, derive from machine-specific data
26      private const val TRANSFORMATION = "AES/GCM/NoPadding"
...
30      private val storageDir: File by lazy {
31          val appDir = JvmFileSystemUtil.getAppDataDirectory()
32          appDir
33      }
35      private val keyStoreFile: File by lazy { File(storageDir, KEYSTORE_FILE) }
36      private val dataFile: File by lazy { File(storageDir, DATA_FILE) }
38      private fun getOrCreateKeyStore(): KeyStore {
39          val keyStore = KeyStore.getInstance("PKCS12")
41          if (keyStoreFile.exists()) {
42              keyStoreFile.inputStream().use { stream ->
43                  keyStore.load(stream, KEYSTORE_PASSWORD.toCharArray())
44              }
45          } else {
46              keyStore.load(null, KEYSTORE_PASSWORD.toCharArray())
47          }
49          return keyStore
50      }
52      private fun saveKeyStore(keyStore: KeyStore) {
53          keyStoreFile.outputStream().use { stream ->
54              keyStore.store(stream, KEYSTORE_PASSWORD.toCharArray())
55          }
56      }
58      private fun getOrCreateSecretKey(): SecretKey {
59          val keyStore = getOrCreateKeyStore()
61          return if (keyStore.containsAlias(KEY_ALIAS)) {
62              keyStore.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as SecretKey
63          } else {
...
68              keyStore.setKeyEntry(KEY_ALIAS, secretKey, KEYSTORE_PASSWORD.toCharArray(), null)
69              saveKeyStore(keyStore)
70              secretKey
71          }
72      }
```

The public API (lines 117-148) is `put/get/remove/contains/clear` — **must not change**. It is consumed at ~20 call sites (e.g. `homebase-api/.../youauth/CredentialStorage.kt`, `homebase-api/.../sync/database/DatabaseKeyManager.kt`, `androidApp/.../MainApplication.kt`). The PKCS12 keystore password and the AES key entry's protection-parameter password are the **same** `KEYSTORE_PASSWORD` everywhere it appears.

### `homebase-api/src/jvmMain/kotlin/id/homebase/api/file/JvmFileSystemUtil.kt` (read-only reference)
`getAppDataDirectory()` returns the per-OS app-data dir (macOS `~/Library/Application Support/HomebaseChat[Dev]`, Windows `%APPDATA%\HomebaseChat[Dev]`, Linux `~/.homebase-chat[-dev]`) and `mkdirs()` it. `storageDir` in SecureStorage pulls from here directly — there is **no seam** to redirect it in tests, so this plan adds a minimal internal test override (see Step 1).

### Test conventions / exemplar
- jvmTest uses kotlin.test (`@Test`, `assertEquals`, `assertNull`, `assertTrue`) with FAKES, no Mockito/MockK. Model the new test after `homebase-api/src/jvmTest/kotlin/id/homebase/api/util/DecodeHtmlEntitiesTest.kt` (plain `@Test` methods, no DI).
- jvmTest deps already include `libs.kotlin.test` (`homebase-api/build.gradle.kts:227`), so no build-file change is required for the test.

### Dependency check (decides the approach)
`grep -niE "keyring|keychain|credential|secret-service|dbus|wincred|java-keyring"` over `gradle/libs.versions.toml` returns **nothing** (exit 1). There is **no cross-OS keyring/credential-store library on the classpath**. Per the spec STOP rule, adding one is out of scope. This plan therefore implements **option (c)**: generate a random keystore password on first run, persist it in an OS-protected sidecar file next to the keystore, and migrate existing installs. The threat model and why this is the right scope are documented in Maintenance notes.

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Drift check | `git diff --stat 45e2832e..HEAD -- homebase-api/src/jvmMain/kotlin/id/homebase/api/storage/SecureStorage.jvm.kt` | empty (no drift) |
| Confirm only intended files changed | `git status --porcelain` | only `SecureStorage.jvm.kt` and the new test file under `homebase-api/src/jvmTest/...` (plus this plan) |
| Compile JVM | `./gradlew :homebase-api:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Run the new + existing jvm tests | `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.storage.*" --rerun-tasks` | `BUILD SUCCESSFUL`, new tests green |
| Full module jvm test gate | `./gradlew :homebase-api:jvmTest` | `BUILD SUCCESSFUL` |
| Confirm no other target broke | `./gradlew :homebase-api:compileAndroidMain` | `BUILD SUCCESSFUL` (android actual untouched — sanity) |
| Konsist arch test (strings) | `./gradlew :homebase-common:jvmTest --tests "*ArchitectureTest*"` | `BUILD SUCCESSFUL` (no UI strings added; sanity only) |

## Scope
**In scope (only these files):**
- `homebase-api/src/jvmMain/kotlin/id/homebase/api/storage/SecureStorage.jvm.kt` — replace the constant password with a derived/persisted password; add password resolution, sidecar persistence, a test seam, and migration.
- `homebase-api/src/jvmTest/kotlin/id/homebase/api/storage/SecureStorageJvmTest.kt` — **new** round-trip + migration tests.

**Out of scope (do not touch):**
- `homebase-api/src/androidMain/.../SecureStorage.android.kt` — Android uses the AndroidKeyStore (hardware-backed); unaffected.
- `homebase-api/src/nativeMain/.../SecureStorage.native.kt` — iOS Keychain; unaffected.
- `homebase-api/src/wasmJsMain/.../SecureStorage.web.kt` — Web; unaffected.
- `homebase-api/src/commonMain/.../SecureStorage.kt` (the `expect`) — public API unchanged, so the expect must not change.
- The stored **values** (shared secret, DB key) — never read/print/log them; migration re-keys the container, not the contents.
- `JvmFileSystemUtil.kt` — read-only reference; do not modify.
- `gradle/libs.versions.toml` / `build.gradle.kts` — no new dependency (STOP if you think one is needed).

## Steps

Implement all changes inside `SecureStorage.jvm.kt`. The design:
1. A **sidecar file** `secure_storage.key` in `storageDir` holds a Base64 per-install random password (created on first run, `0600`/owner-only where supported).
2. `resolveKeyStorePassword()` returns the sidecar password, deriving/creating it if absent.
3. On open, if loading the keystore with the **new** password fails *and* the keystore exists, attempt the **legacy** constant password and, on success, **re-key** (re-store under the new password) — that is the migration.
4. A `private var` storageDir override gives jvmTest a writable temp dir without hitting the real app-data dir.

### Step 1 — Add a test seam for `storageDir`
Replace the `by lazy` `storageDir` with an overridable backing field so tests can point it at a temp dir. Keep production behavior identical (defaults to `JvmFileSystemUtil.getAppDataDirectory()`).

Change lines 30-36 from:
```kotlin
    private val storageDir: File by lazy {
        val appDir = JvmFileSystemUtil.getAppDataDirectory()
        appDir
    }

    private val keyStoreFile: File by lazy { File(storageDir, KEYSTORE_FILE) }
    private val dataFile: File by lazy { File(storageDir, DATA_FILE) }
```
to a settable seam (note: `keyStoreFile`/`dataFile`/`keyFile` must become functions or recomputed getters, not `by lazy`, so a test override of the dir is honored):
```kotlin
    // Test seam: defaults to the real app-data dir in production. Tests set this
    // to a temp dir BEFORE the first SecureStorage call. Not part of the public API.
    @Volatile
    internal var storageDirOverride: File? = null

    private val storageDir: File
        get() = storageDirOverride ?: JvmFileSystemUtil.getAppDataDirectory()

    private val keyStoreFile: File get() = File(storageDir, KEYSTORE_FILE)
    private val dataFile: File get() = File(storageDir, DATA_FILE)
    private val keyFile: File get() = File(storageDir, KEY_FILE)
```
Add the new constant near the other file-name constants (lines 21-23 area):
```kotlin
    private const val KEY_FILE = "secure_storage.key"
```
Verify: `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL` (the rest still references `KEYSTORE_PASSWORD`, so it compiles).

### Step 2 — Rename the constant to make its legacy role explicit, and add password resolution
Rename `KEYSTORE_PASSWORD` to `LEGACY_KEYSTORE_PASSWORD` (lines 24-25) so every remaining reference is obviously the *migration-only* path, then introduce the resolver. Do NOT reproduce the literal value anywhere new — keep the existing string token exactly as-is, only rename the identifier:
```kotlin
    // Legacy compile-time password — kept ONLY to read keystores created before
    // the per-install password migration (see resolveKeyStorePassword / migration
    // in getOrCreateKeyStore). Considered burned; never used to WRITE a keystore.
    private const val LEGACY_KEYSTORE_PASSWORD =
        "SecureStorageKeyStorePassword"
```
Add the resolver and sidecar I/O (place below the file getters):
```kotlin
    /** Returns the per-install keystore password, creating + persisting it on first use. */
    private fun resolveKeyStorePassword(): CharArray {
        val existing = readKeyFile()
        if (existing != null) return existing

        val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val password = Base64.getEncoder().encodeToString(generated).toCharArray()
        writeKeyFile(password)
        return password
    }

    private fun readKeyFile(): CharArray? {
        if (!keyFile.exists()) return null
        val text = keyFile.readText(Charsets.UTF_8).trim()
        return if (text.isEmpty()) null else text.toCharArray()
    }

    private fun writeKeyFile(password: CharArray) {
        keyFile.writeText(String(password), Charsets.UTF_8)
        // Best-effort owner-only perms (POSIX). No-op / ignored on Windows.
        runCatching {
            val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            java.nio.file.Files.setPosixFilePermissions(keyFile.toPath(), perms)
        }
    }
```
Do NOT compile yet — `getOrCreateKeyStore`/`saveKeyStore`/`getOrCreateSecretKey` still reference the now-renamed identifier and will fail to resolve. Move straight to Step 3 in the same edit pass.

### Step 3 — Route open/save/key-access through the resolved password, with migration on open
Rewrite `getOrCreateKeyStore` (lines 38-50) to try the new password first and fall back to the legacy constant **only to migrate**:
```kotlin
    private fun getOrCreateKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")
        val password = resolveKeyStorePassword()

        if (keyStoreFile.exists()) {
            try {
                keyStoreFile.inputStream().use { stream -> keyStore.load(stream, password) }
            } catch (_: Exception) {
                // Migration: existing keystore was written under the legacy constant.
                // Read it with the legacy password, then re-store under `password`.
                keyStoreFile.inputStream().use { stream ->
                    keyStore.load(stream, LEGACY_KEYSTORE_PASSWORD.toCharArray())
                }
                migrateKeyEntry(keyStore, password)
                keyStoreFile.outputStream().use { stream -> keyStore.store(stream, password) }
            }
        } else {
            keyStore.load(null, password)
        }
        return keyStore
    }

    /**
     * Re-protect the AES key entry under the new password. PKCS12 stores the key
     * entry with its own protection param; on a legacy keystore that param is the
     * legacy constant, so we must re-set the entry under [newPassword] before storing.
     */
    private fun migrateKeyEntry(keyStore: KeyStore, newPassword: CharArray) {
        if (!keyStore.containsAlias(KEY_ALIAS)) return
        val key = keyStore.getKey(KEY_ALIAS, LEGACY_KEYSTORE_PASSWORD.toCharArray()) as SecretKey
        keyStore.setKeyEntry(KEY_ALIAS, key, newPassword, null)
    }
```
Rewrite `saveKeyStore` (lines 52-56) to use the resolved password:
```kotlin
    private fun saveKeyStore(keyStore: KeyStore) {
        keyStoreFile.outputStream().use { stream ->
            keyStore.store(stream, resolveKeyStorePassword())
        }
    }
```
Rewrite the two `KEYSTORE_PASSWORD` uses in `getOrCreateSecretKey` (lines 62, 68). Because `getOrCreateKeyStore` already migrated the entry to the resolved password, key access here uses the resolved password:
```kotlin
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = getOrCreateKeyStore()
        val password = resolveKeyStorePassword()

        return if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.getKey(KEY_ALIAS, password) as SecretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(256, SecureRandom())
            val secretKey = keyGenerator.generateKey()
            keyStore.setKeyEntry(KEY_ALIAS, secretKey, password, null)
            saveKeyStore(keyStore)
            secretKey
        }
    }
```
After this edit, **no** remaining reference to `LEGACY_KEYSTORE_PASSWORD` should exist except in `getOrCreateKeyStore` and `migrateKeyEntry`. Confirm:
- Verify: `grep -n "LEGACY_KEYSTORE_PASSWORD\|KEYSTORE_PASSWORD" homebase-api/src/jvmMain/kotlin/id/homebase/api/storage/SecureStorage.jvm.kt` -> only `LEGACY_KEYSTORE_PASSWORD` appears (3 sites: the const decl, the catch-block load, and `migrateKeyEntry`), and **zero** bare `KEYSTORE_PASSWORD`.
- Verify: `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

### Step 4 — Add the round-trip + migration jvmTest
Create `homebase-api/src/jvmTest/kotlin/id/homebase/api/storage/SecureStorageJvmTest.kt`. It must (a) point SecureStorage at a temp dir via `storageDirOverride`, (b) round-trip put/get/remove/contains/clear, and (c) prove migration: write a keystore under the legacy password by hand, drop a value encrypted with that key into the properties file, then assert SecureStorage reads it back and re-keys (sidecar key file appears, keystore now opens with the new password). See Test plan for the full body.
- Verify: `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.storage.*" --rerun-tasks` -> `BUILD SUCCESSFUL`, both tests green.

### Step 5 — Full gate + scope check
- Verify: `./gradlew :homebase-api:jvmTest` -> `BUILD SUCCESSFUL` (no regression in the module).
- Verify: `./gradlew :homebase-api:compileAndroidMain` -> `BUILD SUCCESSFUL` (Android actual untouched; multiplatform still resolves).
- Verify: `git status --porcelain` -> only `SecureStorage.jvm.kt`, the new test file, and this plan are modified/added.

## Test plan
New file `homebase-api/src/jvmTest/kotlin/id/homebase/api/storage/SecureStorageJvmTest.kt`, modeled after `DecodeHtmlEntitiesTest.kt` (plain kotlin.test, no DI/mocks). Because `SecureStorage` is a JVM `object` with mutable static state, each test sets `storageDirOverride` to a fresh temp dir, calls `clear()` to reset in-dir state, and the migration test uses its own temp dir so it never collides with the round-trip test.

Cases:
1. **`put_get_roundtrips`** — set `storageDirOverride` to `createTempDir()`; `SecureStorage.put("k","v")`; assert `get("k") == "v"`; assert `contains("k")`; assert the sidecar `secure_storage.key` file now exists and is non-empty (the new password was generated and persisted). Asserts the password is no longer a compile-time constant.
2. **`remove_and_clear`** — put two keys, `remove` one (`assertNull(get)` for it, value still there for the other), then `clear()` and assert both gone.
3. **`get_missing_returns_null`** — `assertNull(SecureStorage.get("nope"))`.
4. **`migrates_legacy_keystore_and_rekeys`** (the regression this fixes) — in a fresh temp dir, **without** a sidecar key file:
   - Build a PKCS12 keystore in-test, generate an AES-256 key, `setKeyEntry(KEY_ALIAS, key, LEGACY_PASSWORD, null)`, store it to `secure_storage.p12` under `LEGACY_PASSWORD`.
   - Encrypt a known plaintext (`"legacy-secret"`) with that AES key using `AES/GCM/NoPadding` (IV prepended, Base64), write it into `secure_storage.properties` under key `"legacy"` — i.e. exactly the on-disk format `decrypt()` expects.
   - Point `storageDirOverride` at that dir.
   - Assert `SecureStorage.get("legacy") == "legacy-secret"` (proves the legacy keystore was opened and the legacy-encrypted value decrypts).
   - Assert the sidecar `secure_storage.key` now exists (migration generated + persisted the new password).
   - Assert the on-disk keystore now opens with the **new** sidecar password and **fails** to load with the legacy password (proves re-key happened, not just a transparent read). Load `secure_storage.p12` directly with `KeyStore.getInstance("PKCS12")` using the bytes from the sidecar file as the password; expect success. Loading with `LEGACY_PASSWORD` should throw.

   Use the literal legacy token (`"SecureStorageKeyStorePassword"`) **only inside this test** to construct the legacy keystore — it is the documented migration fixture, not a new secret. Do not log it.

Model the GCM encrypt fixture on the production `encrypt()` shape (lines 87-100): 12-byte IV prepended, `GCMParameterSpec(128, iv)`, Base64 of `iv || ciphertext`.

Verify command: `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.storage.*" --rerun-tasks` -> `BUILD SUCCESSFUL` with 4 passing tests.

## Done criteria
- [ ] `grep -c "KEYSTORE_PASSWORD" SecureStorage.jvm.kt` shows the identifier is **only** `LEGACY_KEYSTORE_PASSWORD` and only at the 3 migration sites; no production write/open uses a compile-time password.
- [ ] No keystore is ever **written** (`store(...)`) with `LEGACY_KEYSTORE_PASSWORD` — only read.
- [ ] A new keystore is created under a per-install random Base64 password persisted to `secure_storage.key`.
- [ ] Public API (`put/get/remove/contains/clear`) signatures unchanged; `commonMain` expect untouched.
- [ ] `./gradlew :homebase-api:compileKotlinJvm` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-api:compileAndroidMain` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-api:jvmTest` → `BUILD SUCCESSFUL`, including the 4 new tests.
- [ ] No new entry in `gradle/libs.versions.toml` or `build.gradle.kts`.
- [ ] `git status --porcelain` lists only the two source/test files and this plan.
- [ ] plans/README.md row for Plan 016 updated to Done.

## STOP conditions
- **STOP** if the Drift check shows `SecureStorage.jvm.kt` already changed from the excerpts above (someone may have fixed/moved this) — re-read and report.
- **STOP and report** if you conclude a robust solution requires a cross-OS OS-keyring native dependency (java-keyring, secret-service/DBus, wincred, macOS Keychain JNI) — none is on the classpath today, and adding a new native lib is explicitly out of scope. Report the option (a) keyring path and the option (b) "bind to user/home + per-install salt" path as follow-ups; ship option (c) (random per-install password + sidecar + migration) as planned here.
- **STOP** if you cannot give the sidecar file owner-only permissions on a POSIX host AND the test environment shows the file is world-readable — note it; do not weaken the migration. (On Windows the POSIX call is a documented no-op; that is acceptable for this scope.)
- **STOP** if any existing `:homebase-api:jvmTest` test starts failing after your change — the object holds static state; a leaked `storageDirOverride` could bleed between tests. Ensure each test resets it (e.g. `@AfterTest { SecureStorage.storageDirOverride = null }`).
- **NEVER** print, log, or write the stored values or the legacy/new passwords into logs or the plan.

## Maintenance notes
- **Threat model (what option (c) does and does not buy).** Before: one global constant in the binary decrypts every install's data given only the data dir. After: an attacker needs the data dir **and** that machine's `secure_storage.key` sidecar; the password is unique per install, so breaking one does not break others, and the constant in the binary is inert (legacy-read-only). What it does **not** defend against: an attacker who can read the whole app-data dir already gets the sidecar that sits next to the keystore — so this raises the bar (no shared global secret, per-install isolation, defeats binary-only and cross-install attacks) but does not make a fully-readable home dir safe. The robust next step is to move the sidecar password into an **OS-protected store** (macOS Keychain, Windows DPAPI/Credential Manager, Linux Secret Service) so it is not co-located with the keystore — that is option (a) and needs a native dependency, hence a separate plan. Document this honestly; do not over-claim.
- A reviewer should scrutinize: (1) that `store(...)` is never called with the legacy password — only `load(...)`; (2) that `migrateKeyEntry` re-sets the entry under the new password **before** the keystore is re-stored (PKCS12 protects the key entry with its own param, so a plain `store` under the new password without re-setting the entry would leave the entry unreadable later); (3) that the sidecar is created atomically enough that a crash mid-first-run does not leave a keystore written under a password whose sidecar was never persisted (the order in Step 3 is: resolve password → write sidecar → then write keystore; keep that order); (4) that `storageDirOverride` is `internal` (not public) and reset between tests.
- Deferred follow-ups: (a) OS-keyring-backed sidecar (separate plan, needs dependency review against the disk-full/worktree constraints); (b) consider a one-time re-encrypt that also rotates the AES key (not just the keystore password) for installs that were exposed — out of scope here because it would force a re-derive of the DB key and shared secret.
- The Konsist `ArchitectureTest` only inspects Composables for string literals; this change adds none, so it is unaffected (the gate command is listed only as a sanity check).
