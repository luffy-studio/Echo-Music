package echo.music.iad1tya.playback

class LameMp3Encoder(
  inSampleRate: Int,
  numChannels: Int,
  outSampleRate: Int = inSampleRate,
  bitrateKbps: Int = 192,
  quality: Int = 2,
) : AutoCloseable {

  private var handle: Long =
    LameNative.initEncoder(
      inSampleRate = inSampleRate,
      numChannels = numChannels,
      outSampleRate = outSampleRate,
      bitrateKbps = bitrateKbps,
      quality = quality,
    )

  init {
    check(handle != 0L) { "Failed to initialize native LAME encoder" }
  }

  fun encodeChunk(pcmBuffer: ShortArray, numSamples: Int, mp3OutputBuffer: ByteArray): Int {
    check(handle != 0L) { "LAME encoder is closed" }
    val encoded =
      LameNative.encodeBuffer(handle, pcmBuffer, numSamples, mp3OutputBuffer, mp3OutputBuffer.size)
    if (encoded < 0) {
      error("LAME encoding error: $encoded")
    }
    return encoded
  }

  fun flush(mp3OutputBuffer: ByteArray): Int {
    check(handle != 0L) { "LAME encoder is closed" }
    val flushed = LameNative.flush(handle, mp3OutputBuffer, mp3OutputBuffer.size)
    if (flushed < 0) {
      error("LAME flush error: $flushed")
    }
    return flushed
  }

  override fun close() {
    if (handle != 0L) {
      LameNative.closeEncoder(handle)
      handle = 0L
    }
  }
}
