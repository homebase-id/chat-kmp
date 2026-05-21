package id.homebase.chat.services

// In a browser, an "in-process file path" only exists in our in-memory
// FileSystem (see step 5 of the wasm pre-flight plan). Until that's wired,
// treat scheme-prefixed URIs (blob:, http(s):, data:) as opaque-but-valid,
// and assume any non-scheme path is missing.
internal actual fun fileExists(path: String): Boolean = path.contains("://")
