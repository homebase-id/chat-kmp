package id.homebase.chat.services.calling

/** Puts the OS audio session into call mode and moves output between earpiece, speaker and headsets. */
expect class CallAudioRouter() {
    /**
     * Enters call mode and returns the route it picked: a headset when present, else speaker for video, earpiece for voice;
     * once started, returns the current route. [onRouteChanged] hears every later switch the router makes on its own.
     */
    fun start(video: Boolean, onRouteChanged: (AudioRoute) -> Unit): AudioRoute
    fun setRoute(route: AudioRoute)
    fun stop()
}
