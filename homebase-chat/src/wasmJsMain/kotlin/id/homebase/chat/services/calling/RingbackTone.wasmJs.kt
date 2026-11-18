package id.homebase.chat.services.calling

actual fun platformRingbackTone(): RingbackTone = object : RingbackTone {
    override fun start() = Unit
    override fun stop() = Unit
}
