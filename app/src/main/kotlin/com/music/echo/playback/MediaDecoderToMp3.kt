package echo.music.iad1tya.playback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object MediaDecoderToMp3 {
  suspend fun decodeAndEncodeToMp3(
    sourceFile: File,
    outputFile: File,
    bitrateKbps: Int = 192,
    quality: Int = 2,
    onProgress: suspend (Int) -> Unit = {},
  ) {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(sourceFile.absolutePath)
      var audioTrackIndex = -1
      var format: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val trackFormat = extractor.getTrackFormat(i)
        val mime = trackFormat.getString(MediaFormat.KEY_MIME)
        if (mime != null && mime.startsWith("audio/")) {
          audioTrackIndex = i
          format = trackFormat
          break
        }
      }
      check(audioTrackIndex >= 0 && format != null) { "No audio track found in source file" }
      extractor.selectTrack(audioTrackIndex)

      val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing MIME type")
      val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      val numChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      val durationUs = format.getTrackDurationUs()

      val decoder = MediaCodec.createDecoderByType(mime)
      decoder.configure(format, null, null, 0)
      decoder.start()

      val encoder =
        LameMp3Encoder(
          inSampleRate = sampleRate,
          numChannels = numChannels,
          outSampleRate = sampleRate,
          bitrateKbps = bitrateKbps,
          quality = quality,
        )

      val outputStream = FileOutputStream(outputFile)
      val bufferInfo = MediaCodec.BufferInfo()
      val mp3OutputBuffer = ByteArray(128 * 1024)

      var isInputEof = false
      var isOutputEof = false

      try {
        while (!isOutputEof) {
          currentCoroutineContext().ensureActive()

          if (!isInputEof) {
            val inputBufferIndex = decoder.dequeueInputBuffer(10_000L)
            if (inputBufferIndex >= 0) {
              val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
              if (inputBuffer != null) {
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                  decoder.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    0,
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                  )
                  isInputEof = true
                } else {
                  val presentationTimeUs = extractor.sampleTime
                  decoder.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    sampleSize,
                    presentationTimeUs,
                    0,
                  )
                  extractor.advance()
                }
              }
            }
          }

          val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
          if (outputBufferIndex >= 0) {
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
              isOutputEof = true
            }

            if (bufferInfo.size > 0) {
              val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
              if (outputBuffer != null) {
                outputBuffer.position(bufferInfo.offset)
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                val pcmShorts = ShortArray(bufferInfo.size / 2)
                outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmShorts)

                val numSamplesPerChannel = pcmShorts.size / numChannels
                val encodedBytes =
                  encoder.encodeChunk(pcmShorts, numSamplesPerChannel, mp3OutputBuffer)
                if (encodedBytes > 0) {
                  outputStream.write(mp3OutputBuffer, 0, encodedBytes)
                }

                if (durationUs > 0) {
                  val percent =
                    (bufferInfo.presentationTimeUs * 40 / durationUs).toInt().coerceIn(0, 40)
                  onProgress(50 + percent)
                }
              }
            }
            decoder.releaseOutputBuffer(outputBufferIndex, false)
          }
        }

        val flushedBytes = encoder.flush(mp3OutputBuffer)
        if (flushedBytes > 0) {
          outputStream.write(mp3OutputBuffer, 0, flushedBytes)
        }
        outputStream.flush()
      } finally {
        outputStream.close()
        try {
          decoder.stop()
        } catch (_: Exception) {}
        decoder.release()
        encoder.close()
      }
    } finally {
      extractor.release()
    }
  }

  private fun MediaFormat.getTrackDurationUs(): Long =
    try {
      if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION) else -1L
    } catch (_: Exception) {
      -1L
    }
}
