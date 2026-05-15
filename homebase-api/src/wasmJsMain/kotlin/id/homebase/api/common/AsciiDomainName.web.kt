package id.homebase.api.common

// Pre-flight stub: passes through input unchanged. Domains used in chat are
// already in puny-code on the wire, so toAscii on an already-ascii string is
// a no-op; toUnicode would need a real IDN library (e.g. a wasmJs port of
// ICU) but isn't required for the initial wasm bring-up.
actual object Idn {
    actual fun toAscii(idn: String): String = idn
    actual fun toUnicode(puny: String): String = puny
}
