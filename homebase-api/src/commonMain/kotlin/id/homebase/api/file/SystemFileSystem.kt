package id.homebase.api.file

import okio.FileSystem

/**
 * Multiplatform handle to the OS filesystem via okio. Use this from
 * commonMain instead of `okio.FileSystem.SYSTEM`, which is JVM/Android/Native
 * only — wasmJs doesn't ship a real filesystem.
 *
 * - jvm / android / native: backed by `okio.FileSystem.SYSTEM`.
 * - wasmJs: an in-memory `okio.fakefilesystem.FakeFileSystem`. Persistence
 *   in the browser (IndexedDB / OPFS / Cache API) is intentionally deferred
 *   — first-pass wasm support keeps Coil's `DiskCache`,
 *   `DriveFileProviderCached`, and friends functional without disk.
 *
 * For ordinary whole-file or streaming I/O, prefer
 * [FileOperationsProvider] — this val is the escape hatch for code that
 * needs okio's atomic `writeInt`/`readUtf8` binary serialization or
 * recursive directory deletion.
 */
expect val systemFileSystem: FileSystem
