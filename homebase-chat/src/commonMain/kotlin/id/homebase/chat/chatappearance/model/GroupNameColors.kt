package id.homebase.chat.chatappearance.model

import kotlin.math.abs

data class GroupNameColor(val lightTheme: Long, val darkTheme: Long)

object GroupNameColors {
    val palette: List<GroupNameColor> = listOf(
        GroupNameColor(0xFF006DA3, 0xFF00A7FA),
        GroupNameColor(0xFF007A3D, 0xFF00B85C),
        GroupNameColor(0xFFC13215, 0xFFFF6F52),
        GroupNameColor(0xFFB814B8, 0xFFF65AF6),
        GroupNameColor(0xFF5B6976, 0xFF8BA1B6),
        GroupNameColor(0xFF3D7406, 0xFF5EB309),
        GroupNameColor(0xFFCC0066, 0xFFF76EB2),
        GroupNameColor(0xFF2E51FF, 0xFF8599FF),
        GroupNameColor(0xFF9C5711, 0xFFD5920B),
        GroupNameColor(0xFF007575, 0xFF00B2B2),
        GroupNameColor(0xFFD00B4D, 0xFFFF6B9C),
        GroupNameColor(0xFF8F2AF4, 0xFFBF80FF),
        GroupNameColor(0xFFD00B0B, 0xFFFF7070),
        GroupNameColor(0xFF067906, 0xFF0AB80A),
        GroupNameColor(0xFF5151F6, 0xFF9494FF),
        GroupNameColor(0xFF866118, 0xFFD68F00),
        GroupNameColor(0xFF067953, 0xFF00B87A),
        GroupNameColor(0xFFA20CED, 0xFFCF7CF8),
        GroupNameColor(0xFF4B7000, 0xFF74AD00),
        GroupNameColor(0xFFC70A88, 0xFFF76EC9),
        GroupNameColor(0xFFB34209, 0xFFF57A3D),
        GroupNameColor(0xFF06792D, 0xFF0AB844),
        GroupNameColor(0xFF7A3DF5, 0xFFAF8AF9),
        GroupNameColor(0xFF6B6B24, 0xFFA4A437),
        GroupNameColor(0xFFD00B2C, 0xFFF77389),
        GroupNameColor(0xFF2D7906, 0xFF42B309),
        GroupNameColor(0xFFAF0BD0, 0xFFE06EF7),
        GroupNameColor(0xFF32763E, 0xFF4BAF5C),
        GroupNameColor(0xFF2662D9, 0xFF7DA1E8),
        GroupNameColor(0xFF76681E, 0xFFB89B0A),
        GroupNameColor(0xFF067462, 0xFF09B397),
        GroupNameColor(0xFF6447F5, 0xFFA18FF9),
        GroupNameColor(0xFF5E6E0C, 0xFF8FAA09),
        GroupNameColor(0xFF077288, 0xFF00AED1),
        GroupNameColor(0xFFC20AA3, 0xFFF75FDD),
        GroupNameColor(0xFF2D761E, 0xFF43B42D),
    )

    fun getColor(odinId: String, isDarkTheme: Boolean): Long {
        val index = abs(odinId.hashCode()) % palette.size
        return if (isDarkTheme) palette[index].darkTheme else palette[index].lightTheme
    }
}
