package id.homebase.api.util

// Truncate a string to maxVisibleCharacters (be sure UTF characters aren't chopped in the middle)
fun String.truncateToCodePoints(maxVisibleCharacters: Int): String {
    if (maxVisibleCharacters <= 0) return ""
    var codePointCount = 0
    var charIndex = 0
    while (charIndex < length && codePointCount < maxVisibleCharacters) {
        if (charIndex + 1 < length && this[charIndex].isHighSurrogate() && this[charIndex + 1].isLowSurrogate()) {
            charIndex += 2
        } else {
            charIndex += 1
        }
        codePointCount += 1
    }
    return substring(0, charIndex)
}
