package id.homebase.chat.services.calling

/** Puts the OS audio session into call mode and moves output between earpiece, speaker and headsets. */
expect class CallAudioRouter() {
    fun start()
    fun setRoute(route: AudioRoute)
    fun stop()
}
