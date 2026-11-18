package id.homebase.chat.services.calling

/** Tests never play the real tone through the speaker. */
object SilentRingbackTone : RingbackTone {
    override fun start() = Unit
    override fun stop() = Unit
}
