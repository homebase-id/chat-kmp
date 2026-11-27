package id.homebase.core.location

/**
 * Builds an Android-compatible `geo:` URI that iOS's `Maps.app` and Android's map handlers both
 * accept. Includes the address as a query so the receiving map app can show a labeled pin.
 */
fun buildGeoUri(lat: Double, lon: Double, address: String): String {
    val q = "$lat,$lon"
    return if (address.isBlank()) "geo:$q" else "geo:$q?q=$q(${urlEncode(address)})"
}

private fun urlEncode(input: String): String {
    val builder = StringBuilder(input.length)
    for (c in input) {
        if (c.isLetterOrDigit() || c in "-_.~") {
            builder.append(c)
        } else if (c == ' ') {
            builder.append("%20")
        } else {
            for (b in c.toString().encodeToByteArray()) {
                builder.append('%')
                builder.append(((b.toInt() shr 4) and 0xF).toString(16).uppercase())
                builder.append((b.toInt() and 0xF).toString(16).uppercase())
            }
        }
    }
    return builder.toString()
}
