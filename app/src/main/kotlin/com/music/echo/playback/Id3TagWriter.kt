package echo.music.iad1tya.playback

import java.io.File
import java.io.FileOutputStream

object Id3TagWriter {
  fun writeId3v2Tag(
    outputFile: File,
    title: String,
    artist: String,
    album: String,
    year: Int?,
    artworkFile: File?,
  ) {
    if (!outputFile.exists() || outputFile.length() == 0L) return
    val originalBytes = outputFile.readBytes()
    val id3TagBytes = buildId3v2Tag(title, artist, album, year, artworkFile)
    FileOutputStream(outputFile).use { out ->
      out.write(id3TagBytes)
      out.write(originalBytes)
      out.flush()
    }
  }

  private fun buildId3v2Tag(
    title: String,
    artist: String,
    album: String,
    year: Int?,
    artworkFile: File?,
  ): ByteArray {
    val frames = mutableListOf<ByteArray>()
    if (title.isNotBlank()) frames.add(createTextFrame("TIT2", title))
    if (artist.isNotBlank()) frames.add(createTextFrame("TPE1", artist))
    if (album.isNotBlank()) frames.add(createTextFrame("TALB", album))
    if (year != null && year > 0) frames.add(createTextFrame("TYER", year.toString()))
    if (artworkFile != null && artworkFile.exists() && artworkFile.length() > 0) {
      val artBytes = artworkFile.readBytes()
      frames.add(createPictureFrame(artBytes))
    }

    val bodySize = frames.sumOf { it.size }
    val header = ByteArray(10)
    header[0] = 'I'.code.toByte()
    header[1] = 'D'.code.toByte()
    header[2] = '3'.code.toByte()
    header[3] = 3 // ID3v2.3
    header[4] = 0 // Revision
    header[5] = 0 // Flags

    val synchsafeSize = encodeSynchsafe(bodySize)
    System.arraycopy(synchsafeSize, 0, header, 6, 4)

    val result = ByteArray(10 + bodySize)
    System.arraycopy(header, 0, result, 0, 10)
    var pos = 10
    for (frame in frames) {
      System.arraycopy(frame, 0, result, pos, frame.size)
      pos += frame.size
    }
    return result
  }

  private fun createTextFrame(frameId: String, text: String): ByteArray {
    val textBytes = text.toByteArray(Charsets.UTF_8)
    val bodySize = 1 + textBytes.size // 1 byte for encoding (0x03 = UTF-8)
    val frame = ByteArray(10 + bodySize)
    System.arraycopy(frameId.toByteArray(Charsets.US_ASCII), 0, frame, 0, 4)
    frame[4] = (bodySize shr 24 and 0xFF).toByte()
    frame[5] = (bodySize shr 16 and 0xFF).toByte()
    frame[6] = (bodySize shr 8 and 0xFF).toByte()
    frame[7] = (bodySize and 0xFF).toByte()
    frame[8] = 0
    frame[9] = 0
    frame[10] = 3 // UTF-8
    System.arraycopy(textBytes, 0, frame, 11, textBytes.size)
    return frame
  }

  private fun createPictureFrame(imageBytes: ByteArray): ByteArray {
    val mimeBytes = "image/jpeg".toByteArray(Charsets.US_ASCII)
    val headerSize = 1 + mimeBytes.size + 1 + 1 + 1
    val bodySize = headerSize + imageBytes.size
    val frame = ByteArray(10 + bodySize)
    System.arraycopy("APIC".toByteArray(Charsets.US_ASCII), 0, frame, 0, 4)
    frame[4] = (bodySize shr 24 and 0xFF).toByte()
    frame[5] = (bodySize shr 16 and 0xFF).toByte()
    frame[6] = (bodySize shr 8 and 0xFF).toByte()
    frame[7] = (bodySize and 0xFF).toByte()
    frame[8] = 0
    frame[9] = 0
    var pos = 10
    frame[pos++] = 0
    System.arraycopy(mimeBytes, 0, frame, pos, mimeBytes.size)
    pos += mimeBytes.size
    frame[pos++] = 0
    frame[pos++] = 3
    frame[pos++] = 0
    System.arraycopy(imageBytes, 0, frame, pos, imageBytes.size)
    return frame
  }

  private fun encodeSynchsafe(size: Int): ByteArray {
    val b = ByteArray(4)
    b[0] = (size shr 21 and 0x7F).toByte()
    b[1] = (size shr 14 and 0x7F).toByte()
    b[2] = (size shr 7 and 0x7F).toByte()
    b[3] = (size and 0x7F).toByte()
    return b
  }
}
