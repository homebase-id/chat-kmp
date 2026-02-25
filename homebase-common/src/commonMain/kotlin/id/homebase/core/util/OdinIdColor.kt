package id.homebase.core.util

import androidx.compose.ui.graphics.Color

/**
 * Represents a pair of colors for light and dark themes, used to give each OdinId a deterministic,
 * unique color for sender identification in group chats.
 */
data class OdinIdColorValue(
    val lightTheme: Color,
    val darkTheme: Color,
)

/**
 * A palette of 36 curated color pairs, matching the Signal/Homebase web client palette. Each entry
 * provides good contrast on both light and dark backgrounds.
 */
private val OdinIdColorValues: List<OdinIdColorValue> = listOf(
    OdinIdColorValue(Color(0xFF006DA3), Color(0xFF00A7FA)),
    OdinIdColorValue(Color(0xFF007A3D), Color(0xFF00B85C)),
    OdinIdColorValue(Color(0xFFC13215), Color(0xFFFF6F52)),
    OdinIdColorValue(Color(0xFFB814B8), Color(0xFFF65AF6)),
    OdinIdColorValue(Color(0xFF5B6976), Color(0xFF8BA1B6)),
    OdinIdColorValue(Color(0xFF3D7406), Color(0xFF5EB309)),
    OdinIdColorValue(Color(0xFFCC0066), Color(0xFFF76EB2)),
    OdinIdColorValue(Color(0xFF2E51FF), Color(0xFF8599FF)),
    OdinIdColorValue(Color(0xFF9C5711), Color(0xFFD5920B)),
    OdinIdColorValue(Color(0xFF007575), Color(0xFF00B2B2)),
    OdinIdColorValue(Color(0xFFD00B4D), Color(0xFFFF6B9C)),
    OdinIdColorValue(Color(0xFF8F2AF4), Color(0xFFBF80FF)),
    OdinIdColorValue(Color(0xFFD00B0B), Color(0xFFFF7070)),
    OdinIdColorValue(Color(0xFF067906), Color(0xFF0AB80A)),
    OdinIdColorValue(Color(0xFF5151F6), Color(0xFF9494FF)),
    OdinIdColorValue(Color(0xFF866118), Color(0xFFD68F00)),
    OdinIdColorValue(Color(0xFF067953), Color(0xFF00B87A)),
    OdinIdColorValue(Color(0xFFA20CED), Color(0xFFCF7CF8)),
    OdinIdColorValue(Color(0xFF4B7000), Color(0xFF74AD00)),
    OdinIdColorValue(Color(0xFFC70A88), Color(0xFFF76EC9)),
    OdinIdColorValue(Color(0xFFB34209), Color(0xFFF57A3D)),
    OdinIdColorValue(Color(0xFF06792D), Color(0xFF0AB844)),
    OdinIdColorValue(Color(0xFF7A3DF5), Color(0xFFAF8AF9)),
    OdinIdColorValue(Color(0xFF6B6B24), Color(0xFFA4A437)),
    OdinIdColorValue(Color(0xFFD00B2C), Color(0xFFF77389)),
    OdinIdColorValue(Color(0xFF2D7906), Color(0xFF42B309)),
    OdinIdColorValue(Color(0xFFAF0BD0), Color(0xFFE06EF7)),
    OdinIdColorValue(Color(0xFF32763E), Color(0xFF4BAF5C)),
    OdinIdColorValue(Color(0xFF2662D9), Color(0xFF7DA1E8)),
    OdinIdColorValue(Color(0xFF76681E), Color(0xFFB89B0A)),
    OdinIdColorValue(Color(0xFF067462), Color(0xFF09B397)),
    OdinIdColorValue(Color(0xFF6447F5), Color(0xFFA18FF9)),
    OdinIdColorValue(Color(0xFF5E6E0C), Color(0xFF8FAA09)),
    OdinIdColorValue(Color(0xFF077288), Color(0xFF00AED1)),
    OdinIdColorValue(Color(0xFFC20AA3), Color(0xFFF75FDD)),
    OdinIdColorValue(Color(0xFF2D761E), Color(0xFF43B42D)),
)

/**
 * Returns a deterministic color pair for the given OdinId string.
 *
 * Uses XOR of all character codes (matching the web client algorithm) to pick a consistent index
 * into the color palette.
 *
 * @param odinId The domain name string (e.g., "user.example.com")
 * @return The color pair for this identity
 */
fun getOdinIdColor(odinId: String): OdinIdColorValue {
    var c = 0
    for (char in odinId) {
        c = c xor char.code
    }
    return OdinIdColorValues[c % OdinIdColorValues.size]
}
