package id.homebase.api.video.transcoder.interfaces

fun interface TranscoderCancelationSignal {
  fun isCanceled(): Boolean
}
