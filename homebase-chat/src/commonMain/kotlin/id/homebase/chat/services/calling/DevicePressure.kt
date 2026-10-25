package id.homebase.chat.services.calling

internal enum class ThermalLevel { Nominal, Fair, Serious, Critical }

internal data class DevicePressure(val thermal: ThermalLevel = ThermalLevel.Nominal, val lowPower: Boolean = false) {
    val cap: SendCap
        get() = when {
            thermal == ThermalLevel.Critical || lowPower -> SendCap(360, 15)
            thermal == ThermalLevel.Serious -> SendCap(540, SendVideoCap.FPS)
            else -> SendCap.NONE
        }

    fun describe(): String = "device pressure thermal=${thermal.name.lowercase()} lowPower=$lowPower cap=${cap.height}p@${cap.fps}"
}

internal data class SendCap(val height: Int, val fps: Int) {
    companion object {
        val NONE = SendCap(SendVideoCap.HEIGHT, SendVideoCap.FPS)
    }
}
