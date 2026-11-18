package id.homebase.core

internal fun ByteArray.contains(needle: ByteArray): Boolean =
    (0..size - needle.size).any { at -> needle.indices.all { this[at + it] == needle[it] } }
