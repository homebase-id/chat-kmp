package id.homebase.core

interface TextRenderingNudger {
    fun nudge()
}

object TextRenderingHelper {
    private var _nudger: TextRenderingNudger? = null

    fun register(nudger: TextRenderingNudger) {
        _nudger = nudger
    }

    fun nudge() {
        _nudger?.nudge()
    }
}
