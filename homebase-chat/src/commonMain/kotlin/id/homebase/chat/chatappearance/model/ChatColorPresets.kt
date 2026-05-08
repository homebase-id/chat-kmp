package id.homebase.chat.chatappearance.model

object ChatColorPresets {
    val ultramarine = ChatColor.Gradient(
        id = "ultramarine", colorsArgb = listOf(0xFF0553F0, 0xFF2C6CED), angleDegrees = 0f,
    )

    val crimson = ChatColor.Solid(id = "crimson", colorArgb = 0xFFCF163E)
    val vermilion = ChatColor.Solid(id = "vermilion", colorArgb = 0xFFC73F0A)
    val burlap = ChatColor.Solid(id = "burlap", colorArgb = 0xFF6F6A58)
    val forest = ChatColor.Solid(id = "forest", colorArgb = 0xFF3B7845)
    val wintergreen = ChatColor.Solid(id = "wintergreen", colorArgb = 0xFF1D8663)
    val teal = ChatColor.Solid(id = "teal", colorArgb = 0xFF077D92)
    val blue = ChatColor.Solid(id = "blue", colorArgb = 0xFF336BA3)
    val indigo = ChatColor.Solid(id = "indigo", colorArgb = 0xFF6058CA)
    val violet = ChatColor.Solid(id = "violet", colorArgb = 0xFF9932C8)
    val plum = ChatColor.Solid(id = "plum", colorArgb = 0xFFAA377A)
    val taupe = ChatColor.Solid(id = "taupe", colorArgb = 0xFF8F616A)
    val steel = ChatColor.Solid(id = "steel", colorArgb = 0xFF71717F)

    val ember = ChatColor.Gradient(id = "ember", colorsArgb = listOf(0xFFE57C00, 0xFF5E0000), angleDegrees = 162f)
    val midnight = ChatColor.Gradient(id = "midnight", colorsArgb = listOf(0xFF2C2C3A, 0xFF787891), angleDegrees = 180f)
    val infrared = ChatColor.Gradient(id = "infrared", colorsArgb = listOf(0xFFF65560, 0xFF442CED), angleDegrees = 192f)
    val lagoon = ChatColor.Gradient(id = "lagoon", colorsArgb = listOf(0xFF004066, 0xFF32867D), angleDegrees = 180f)
    val fluorescent = ChatColor.Gradient(id = "fluorescent", colorsArgb = listOf(0xFFEC13DD, 0xFF1B36C6), angleDegrees = 192f)
    val basil = ChatColor.Gradient(id = "basil", colorsArgb = listOf(0xFF2F9373, 0xFF077343), angleDegrees = 180f)
    val sublime = ChatColor.Gradient(id = "sublime", colorsArgb = listOf(0xFF6281D5, 0xFF974460), angleDegrees = 180f)
    val sea = ChatColor.Gradient(id = "sea", colorsArgb = listOf(0xFF498FD4, 0xFF2C66A0), angleDegrees = 180f)
    val tangerine = ChatColor.Gradient(id = "tangerine", colorsArgb = listOf(0xFFDB7133, 0xFF911231), angleDegrees = 192f)

    val solids: List<ChatColor.Solid> = listOf(
        crimson, vermilion, burlap, forest, wintergreen, teal,
        blue, indigo, violet, plum, taupe, steel,
    )

    val gradients: List<ChatColor.Gradient> = listOf(
        ultramarine, ember, midnight, infrared, lagoon,
        fluorescent, basil, sublime, sea, tangerine,
    )

    val all: List<ChatColor> = listOf(ultramarine) + solids + gradients.drop(1)

    val default: ChatColor.Gradient = ultramarine

    fun findById(id: String): ChatColor? = all.firstOrNull { it.id == id }
}
