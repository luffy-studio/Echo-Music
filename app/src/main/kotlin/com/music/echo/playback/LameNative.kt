package echo.music.iad1tya.playback

import timber.log.Timber

object LameNative {
  init {
    try {
      System.loadLibrary("echo_lame")
    } catch (e: Throwable) {
      Timber.e(e, "Failed to load native library echo_lame")
    }
  }

  external fun initEncoder(
    inSampleRate: Int,
    numChannels: Int,
    outSampleRate: Int,
    bitrateKbps: Int,
    quality: Int,
  ): Long

  external fun encodeBuffer(
    handle: Long,
    pcmData: ShortArray,
    numSamples: Int,
    mp3Data: ByteArray,
    mp3Capacity: Int,
  ): Int

  external fun flush(
    handle: Long,
    mp3Data: ByteArray,
    mp3Capacity: Int,
  ): Int

  external fun closeEncoder(handle: Long)
}
