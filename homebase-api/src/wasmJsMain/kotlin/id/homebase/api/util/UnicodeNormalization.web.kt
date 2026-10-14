package id.homebase.api.util

private fun normalizeNfcJs(value: String): String = js("value.normalize('NFC')")

actual fun String.toNfc(): String = normalizeNfcJs(this)
