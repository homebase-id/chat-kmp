package id.homebase.api.file

import okio.FileSystem
import okio.fakefilesystem.FakeFileSystem

// In-memory placeholder. Cleared on page refresh; do not rely on it for
// persistence. Step 10 of the WASM pre-flight plan will wire the
// `com.squareup.okio:okio-fakefilesystem` dependency; a future browser
// backing (IndexedDB / OPFS) is out of scope for the pre-flight pass.
actual val systemFileSystem: FileSystem = FakeFileSystem()
