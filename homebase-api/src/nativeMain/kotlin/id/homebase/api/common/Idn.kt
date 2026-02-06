package id.homebase.api.common

actual object Idn {
    actual fun toAscii(idn: String): String {
        // Strict: most domains are ASCII anyway. If you ever need real IDN on iOS,
        // add a pure-Kotlin Punycode library later (none are widely used yet).
        if (idn.any { it.code > 127 }) {
            throw IllegalArgumentException(
                "Non-ASCII (IDN) domains are not supported on Native targets yet. " +
                        "Use only ASCII domains or run on JVM/Android."
            )
        }
        return idn.lowercase()
    }

    actual fun toUnicode(puny: String): String = puny   // already ASCII
}