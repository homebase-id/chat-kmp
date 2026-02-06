package id.homebase.api.common

actual object Idn {
    actual fun toAscii(idn: String): String =
        java.net.IDN.toASCII(idn, java.net.IDN.USE_STD3_ASCII_RULES)

    actual fun toUnicode(puny: String): String =
        java.net.IDN.toUnicode(puny)
}