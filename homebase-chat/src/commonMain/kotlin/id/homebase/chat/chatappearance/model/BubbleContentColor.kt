package id.homebase.chat.chatappearance.model

import kotlin.math.pow

object BubbleContentColor {
    private const val WHITE: Long = 0xFFFFFFFF
    private const val DARK: Long = 0xFF1B1C1F

    fun forBubble(chatColor: ChatColor): Long {
        val lum = relativeLuminance(primaryArgb(chatColor))
        // Crossover luminance for our specific DARK/WHITE pair: ~0.204.
        // Using this instead of the pure-black/white 0.179 ensures the chosen
        // text color always maximises contrast against the bubble background.
        return if (lum > 0.204) DARK else WHITE
    }

    fun primaryArgb(chatColor: ChatColor): Long = when (chatColor) {
        is ChatColor.Solid -> chatColor.colorArgb
        is ChatColor.Gradient -> chatColor.colorsArgb.first()
        is ChatColor.Auto -> primaryArgb(ChatColorPresets.default)
        is ChatColor.NotSet -> primaryArgb(ChatColorPresets.default)
    }

    fun relativeLuminance(argb: Long): Double {
        val r = linearize(((argb shr 16) and 0xFF).toInt())
        val g = linearize(((argb shr 8) and 0xFF).toInt())
        val b = linearize((argb and 0xFF).toInt())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linearize(channel: Int): Double {
        val s = channel / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
}
