package id.homebase.api.util

/** Unicode Normalization Form C, the same on every platform; Filesync exchanges names in NFC. */
expect fun String.toNfc(): String
