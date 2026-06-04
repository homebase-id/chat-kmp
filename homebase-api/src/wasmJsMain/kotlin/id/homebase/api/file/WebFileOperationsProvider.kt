package id.homebase.api.file

/**
 * Web file operations, backed by the in-memory wasm [systemFileSystem] (an okio `FakeFileSystem`).
 * Everything lives in RAM and is cleared on page refresh — adequate for the transient temp files
 * the upload/share pipeline produces; durable browser storage (IndexedDB / OPFS) is out of scope
 * for the first-pass wasm support.
 *
 * All behavior is the shared [OkioFileOperationsProvider]; this just binds it to the web filesystem
 * and cache dir. The logic is unit-tested generically in `OkioFileOperationsProviderTest` against a
 * `FakeFileSystem` (the same FS type [systemFileSystem] is on web).
 */
class WebFileOperationsProvider : OkioFileOperationsProvider(systemFileSystem, "/tmp/homebase")
