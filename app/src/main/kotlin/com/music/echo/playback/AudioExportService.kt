package echo.music.iad1tya.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Uri
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.datastore.preferences.core.edit
import androidx.documentfile.provider.DocumentFile
import androidx.media3.datasource.cache.SimpleCache
import com.music.innertube.YouTube
import dagger.hilt.android.AndroidEntryPoint
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.AudioQuality
import echo.music.iad1tya.constants.EnableExportAsMp3Key
import echo.music.iad1tya.constants.ExportFilesKey
import echo.music.iad1tya.constants.ExportProgressKey
import echo.music.iad1tya.constants.ExportedSongIdsKey
import echo.music.iad1tya.constants.ExportingSongIdsKey
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.SongEntity
import echo.music.iad1tya.di.DownloadCache
import echo.music.iad1tya.di.PlayerCache
import echo.music.iad1tya.extensions.tryOrNull
import echo.music.iad1tya.utils.YTPlayerUtils
import echo.music.iad1tya.utils.YTPlayerUtils.PlaybackData
import echo.music.iad1tya.utils.dataStore
import echo.music.iad1tya.utils.get
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

@AndroidEntryPoint
class AudioExportService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val httpClient = OkHttpClient()
  private val exports = ExportTaskQueue(serviceScope, onIdle = ::finishExports)
  private val activeExportJobs = ConcurrentHashMap.newKeySet<String>()
  private val completedExportIds = ConcurrentHashMap.newKeySet<String>()
  private val lastProgressWritten = ConcurrentHashMap<String, Int>()

  @Inject lateinit var database: MusicDatabase
  @Inject @DownloadCache lateinit var downloadCache: SimpleCache
  @Inject @PlayerCache lateinit var playerCache: SimpleCache

  private val totalSubmitted = AtomicInteger(0)
  private val completedCount = AtomicInteger(0)
  private val failedCount = AtomicInteger(0)

  override fun onCreate() {
    super.onCreate()
    getSystemService<NotificationManager>()
      ?.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.export_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        )
      )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_CANCEL_ALL) {
      activeExportJobs.clear()
      completedExportIds.clear()
      exports.cancelAll()
      httpClient.dispatcher.cancelAll()
      if (exports.isIdle) finishExports()
      return START_NOT_STICKY
    }

    val targetDirectoryUri = intent?.getStringExtra(EXTRA_TARGET_DIRECTORY_URI).orEmpty()
    if (targetDirectoryUri.isBlank()) {
      if (exports.isIdle) stopSelf(startId)
      return START_NOT_STICKY
    }

    val songId = intent?.getStringExtra(EXTRA_SONG_ID)
    if (!songId.isNullOrBlank()) {
      // Idempotency check: Guard against duplicate intent invocations for active or completed songs in this session
      if (activeExportJobs.contains(songId) || completedExportIds.contains(songId)) {
        Timber.i("Export job for songId=%s is already active or completed in this session. Ignoring duplicate request.", songId)
        return START_NOT_STICKY
      }
      activeExportJobs.add(songId)

      val songTitle = intent.getStringExtra(EXTRA_SONG_TITLE).orEmpty()
      val songArtist = intent.getStringExtra(EXTRA_SONG_ARTIST).orEmpty()
      val songAlbum = intent.getStringExtra(EXTRA_SONG_ALBUM).orEmpty()
      val artworkUrl = intent.getStringExtra(EXTRA_ARTWORK_URL).orEmpty()

      val accepted =
        exports.submit(songId) {
          exportSong(
            songId = songId,
            songTitle = songTitle,
            songArtist = songArtist,
            songAlbum = songAlbum,
            artworkUrl = artworkUrl,
            targetDirectoryUri = targetDirectoryUri,
          )
        }
      if (accepted) {
        totalSubmitted.incrementAndGet()
      } else {
        activeExportJobs.remove(songId)
      }
      updateNotification(songTitle)
    }

    return START_NOT_STICKY
  }

  private fun updateNotification(currentTitle: String) {
    val total = totalSubmitted.get()
    val completed = completedCount.get() + failedCount.get() + 1
    val cancelIntent =
      PendingIntent.getService(
        this,
        0,
        Intent(this, AudioExportService::class.java).setAction(ACTION_CANCEL_ALL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val titleText =
      if (total > 1) {
        "Exporting $completed of $total: $currentTitle"
      } else {
        getString(R.string.exporting)
      }

    val builder =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.download)
        .setContentTitle(titleText)
        .setContentText(currentTitle.ifBlank { getString(R.string.export_music) })
        .setOngoing(true)
        .addAction(R.drawable.close, getString(R.string.cancel_exports), cancelIntent)

    if (total > 1) {
      builder.setProgress(total, completed, false)
    }

    startForeground(NOTIFICATION_ID, builder.build())
  }

  private fun findCachedSourceFile(songId: String): File? {
    val downloadSpans = tryOrNull { downloadCache.getCachedSpans(songId) }
    val playerSpans = tryOrNull { playerCache.getCachedSpans(songId) }
    val spans = (downloadSpans ?: emptySet()) + (playerSpans ?: emptySet())
    return spans.firstOrNull { it.file != null && it.file!!.exists() && it.file!!.length() > 0 }?.file
  }

  private suspend fun exportSong(
    songId: String,
    songTitle: String,
    songArtist: String,
    songAlbum: String,
    artworkUrl: String,
    targetDirectoryUri: String,
  ) {
    val safeTitle = sanitizeTitle(songTitle.ifBlank { songId })
    val enableMp3 = dataStore.get(EnableExportAsMp3Key, false)
    val tempFiles = mutableListOf<File>()
    var stage = "resolve"
    val started = android.os.SystemClock.elapsedRealtime()
    var mark = started
    fun timing(label: String) {
      val now = android.os.SystemClock.elapsedRealtime()
      Timber.i("song=%s stage=%s elapsedMs=%d totalMs=%d", songId, label, now - mark, now - started)
      mark = now
    }
    val name = safeTitle
    suspend fun progress(percent: Int, label: String) =
      updateExportProgress(songId, percent, name, label)

    try {
      addExportingSongId(songId)
      progress(0, "Resolving")

      // 120-second export deadline with chunked resilient downloading
      withTimeout(120_000L) {
        val tempSourceFile =
          File.createTempFile("export_source_", ".audio", cacheDir).also(tempFiles::add)

        // Fast Path: Check if cached source file exists on disk in ExoPlayer cache
        val cachedFile = findCachedSourceFile(songId)
        if (cachedFile != null) {
          cachedFile.copyTo(tempSourceFile, overwrite = true)
          timing("downloadedFromCache")
        } else {
          val connectivityManager =
            getSystemService<ConnectivityManager>() ?: error("No connectivity manager")
          val playbackData =
            YTPlayerUtils.playerResponseForPlayback(
                videoId = songId,
                audioQuality = AudioQuality.AUTO,
                connectivityManager = connectivityManager,
              )
              .getOrThrow()

          timing("resolved")
          stage = "download"
          currentCoroutineContext().ensureActive()

          // Resilient Chunked Range Downloader: prevents YouTube connection throttling & socket drops
          downloadStreamChunked(songId, playbackData, tempSourceFile) { percent ->
            progress((percent * 0.5f).toInt(), "Downloading")
          }
          timing("downloadedFromNetwork")
        }

        stage = "inspect"
        val metadata = android.media.MediaMetadataRetriever()
        val durationMs: Long
        val sourceMime: String?
        try {
          metadata.setDataSource(tempSourceFile.absolutePath)
          durationMs =
            metadata
              .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
              ?.toLongOrNull() ?: 0L
          check(
            metadata.extractMetadata(
              android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
            ) == "yes"
          ) {
            "Missing audio track"
          }
          sourceMime =
            metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } finally {
          metadata.release()
        }

        val isSourceMp3 = sourceMime == "audio/mpeg" || sourceMime == "audio/mp3"
        val extension = "mp3"
        val finalFileName = "$safeTitle.$extension"

        val tempResultFile =
          File.createTempFile("export_result_", ".mp3", cacheDir).also(tempFiles::add)
        val tempArtworkFile =
          File.createTempFile("export_cover_", ".jpg", cacheDir).also(tempFiles::add)
        val year = fetchSongYear(songId)
        val artworkDownloaded = downloadArtwork(artworkUrl, tempArtworkFile)

        stage = "convert"
        currentCoroutineContext().ensureActive()
        progress(50, if (isSourceMp3) "Copying" else "Converting")
        timing("conversionPrepared")

        // Ultra-Fast Native LAME MP3 Export Engine (Zero FFmpeg Dependency):
        // 1. Direct stream copy if source is already MP3 (~15-30ms)
        // 2. High-speed native NDK LAME encoder via Android MediaCodec decoder if non-MP3 (~100-300ms)
        if (isSourceMp3) {
          tempSourceFile.copyTo(tempResultFile, overwrite = true)
        } else {
          MediaDecoderToMp3.decodeAndEncodeToMp3(
            sourceFile = tempSourceFile,
            outputFile = tempResultFile,
            bitrateKbps = 192,
            quality = 2,
          ) { p -> progress(p, "Converting") }
        }

        // Embed ID3v2 Tags (Title, Artist, Album, Year, Cover Art) natively
        Id3TagWriter.writeId3v2Tag(
          outputFile = tempResultFile,
          title = songTitle,
          artist = songArtist,
          album = songAlbum,
          year = year,
          artworkFile = if (artworkDownloaded) tempArtworkFile else null,
        )
        timing("converted")

        currentCoroutineContext().ensureActive()
        progress(99, "Saving")
        stage = "destination"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val document =
          writeOutputFile(safeTitle, targetDirectoryUri, tempResultFile, extension, digest)
        Timber.i(
          "song=%s stage=written file=%s bytes=%d format=%s",
          songId,
          document.name ?: finalFileName,
          tempResultFile.length(),
          extension,
        )
        timing("saved")

        // Update Room Database so exported file appears instantly in Library / Downloaded screens
        val downloadDate = LocalDateTime.now()
        database.withTransaction {
          val rowId =
            insert(
              SongEntity(
                id = songId,
                title = songTitle,
                duration = (durationMs / 1000).toInt(),
                thumbnailUrl = artworkUrl.ifBlank { null },
                albumName = songAlbum.ifBlank { null },
                dateDownload = downloadDate,
                isDownloaded = true,
              )
            )
          if (rowId == -1L) {
            updateDownloadedInfo(songId, true, downloadDate)
          }
        }

        // Notify Android MediaScanner if local file path exists
        if (document.uri.scheme == "file" || document.uri.path != null) {
          tryOrNull {
            MediaScannerConnection.scanFile(
              applicationContext,
              arrayOf(document.uri.path ?: document.uri.toString()),
              null,
              null,
            )
          }
        }

        val file =
          JSONObject()
            .put("uri", document.uri.toString())
            .put("name", document.name ?: finalFileName)
            .put("directory", targetDirectoryUri)
            .put("convert", enableMp3)
            .put("title", songTitle)
            .put(
              "hash",
              android.util.Base64.encodeToString(
                digest.digest(),
                android.util.Base64.NO_WRAP,
              ),
            )
            .put("duration", durationMs / 1000)
            .put("artist", songArtist)
            .put("album", songAlbum)
            .put("artwork", artworkUrl)

        addExportedSongId(songId, file)
        completedExportIds.add(songId)
        timing("published")
        progress(100, "Completed")
        completedCount.incrementAndGet()
      }
    } catch (e: Exception) {
      if (e is TimeoutCancellationException) {
        Timber.e("song=%s stage=%s TIMEOUT", songId, stage)
        withContext(NonCancellable) {
          progress(0, "Timed out")
          withContext(Dispatchers.Main) {
            Toast.makeText(applicationContext, "Export timed out. Please try again.", Toast.LENGTH_LONG).show()
          }
        }
      } else if (e is CancellationException) {
        Timber.i("song=%s stage=%s CANCELLED", songId, stage)
        withContext(NonCancellable) { progress(0, "Cancelled") }
        throw e
      } else {
        progress(0, "Failed")
        failedCount.incrementAndGet()
        Timber.e(e, "song=%s stage=%s FAILED reason=%s", songId, stage, e.javaClass.simpleName)
      }
    } finally {
      activeExportJobs.remove(songId)
      tempFiles.forEach(File::delete)
      withContext(NonCancellable) { removeExportingSongId(songId) }
    }
  }

  private suspend fun fetchSongYear(songId: String): Int? =
    YouTube.getMediaInfo(songId).getOrNull()?.uploadDate?.let {
      Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull()
    }

  private suspend fun downloadStreamChunked(
    songId: String,
    playbackData: PlaybackData,
    destFile: File,
    onProgress: suspend (Int) -> Unit = {},
  ) {
    val totalLength = playbackData.format.contentLength ?: -1L
    val streamUrl = playbackData.streamUrl
    val chunkSize = 2 * 1024 * 1024L // 2MB range chunks

    destFile.outputStream().use { output ->
      var bytesDownloaded = 0L
      var chunkStart = 0L

      while (totalLength < 0 || chunkStart < totalLength) {
        currentCoroutineContext().ensureActive()
        val chunkEnd =
          if (totalLength > 0) minOf(chunkStart + chunkSize - 1, totalLength - 1)
          else chunkStart + chunkSize - 1

        var attempts = 0
        var chunkSuccess = false

        while (attempts < 3 && !chunkSuccess) {
          attempts++
          try {
            val requestBuilder =
              Request.Builder()
                .url(streamUrl)
                .header("Range", "bytes=$chunkStart-$chunkEnd")

            ResolvedStream.from(playbackData).headers.forEach { (name, value) ->
              requestBuilder.header(name, value)
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
              if (!response.isSuccessful && response.code != 206 && response.code != 200) {
                if (response.code == 416) {
                  chunkSuccess = true
                  return@use
                }
                error("Chunk request failed with code ${response.code}")
              }

              val body = response.body ?: error("No response body")
              val buffer = ByteArray(65536)
              body.byteStream().use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                  currentCoroutineContext().ensureActive()
                  output.write(buffer, 0, read)
                  bytesDownloaded += read
                }
              }
              chunkSuccess = true
            }
          } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "song=%s chunk retry %d failed", songId, attempts)
            delay(attempts * 150L)
          }
        }

        if (!chunkSuccess) {
          error("Failed to download chunk starting at $chunkStart after retries")
        }

        if (totalLength > 0) {
          val percent = ((bytesDownloaded * 100) / totalLength).toInt().coerceIn(0, 99)
          onProgress(percent)
          chunkStart = chunkEnd + 1
        } else {
          if (bytesDownloaded == chunkStart) break
          chunkStart = bytesDownloaded
        }
      }
      output.flush()
    }
    Timber.i("song=%s stage=received bytes=%d", songId, destFile.length())
    if (destFile.length() <= 0L) {
      error("Incomplete export source: output file is empty")
    }
  }

  private fun downloadArtwork(artworkUrl: String, destFile: File): Boolean {
    if (artworkUrl.isBlank()) return false
    return runCatching {
        httpClient.newCall(Request.Builder().url(artworkUrl).build()).execute().use { response ->
          if (!response.isSuccessful) return@use
          response.body?.byteStream()?.use { input ->
            destFile.outputStream().use { output ->
              input.copyTo(output)
              output.flush()
            }
          }
        }
      }
      .isSuccess && destFile.length() > 0L
  }

  private suspend fun writeOutputFile(
    safeTitle: String,
    targetDirectoryUri: String,
    sourceFile: File,
    extension: String,
    digest: java.security.MessageDigest,
  ): DocumentFile {
    val mimeType = "audio/mpeg"
    val uri = Uri.parse(targetDirectoryUri)
    val targetFileName = "$safeTitle.$extension"

    return if (uri.scheme == "file") {
      val folder = File(uri.path ?: error("Invalid export directory"))
      if (!folder.exists() && !folder.mkdirs()) error("Unable to create export directory")
      val targetFile = File(folder, targetFileName)
      val stagedFile = File.createTempFile("export_stage_", ".$extension", folder)
      try {
        stagedFile.outputStream().use {
          copyExport(sourceFile, java.security.DigestOutputStream(it, digest))
        }
        currentCoroutineContext().ensureActive()
        Files.move(
          stagedFile.toPath(),
          targetFile.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
        )
        DocumentFile.fromFile(targetFile)
      } finally {
        if (stagedFile.exists()) stagedFile.delete()
      }
    } else {
      val destinationDir =
        DocumentFile.fromTreeUri(this, uri) ?: error("Export directory unavailable")

      // Delete existing file with the exact target name first to prevent SAF from creating "Hale Dil (1).mp3"
      destinationDir.findFile(targetFileName)?.delete()

      val outputFile =
        destinationDir.createFile(mimeType, targetFileName)
          ?: error("Unable to create output file $targetFileName")
      try {
        val output =
          contentResolver.openOutputStream(outputFile.uri, "w")
            ?: error("Export file is not writable")
        output.use { copyExport(sourceFile, java.security.DigestOutputStream(it, digest)) }
        outputFile
      } catch (e: Exception) {
        outputFile.delete()
        throw e
      }
    }
  }

  private suspend fun copyExport(sourceFile: File, output: OutputStream) {
    sourceFile.inputStream().use { input ->
      val buffer = ByteArray(65536)
      while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read == -1) break
        output.write(buffer, 0, read)
      }
      output.flush()
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun finishExports() {
    val total = totalSubmitted.get()
    val succ = completedCount.get()
    val fail = failedCount.get()

    serviceScope.launch(NonCancellable) {
      try {
        dataStore.edit { preferences ->
          if (exports.isIdle) {
            preferences.remove(ExportingSongIdsKey)
          }
        }
        if (total > 0) {
          withContext(Dispatchers.Main) {
            val msg =
              if (total > 1) {
                "Export finished: $succ exported, $fail failed"
              } else if (succ == 0 && fail == 0) {
                "Export cancelled"
              } else if (fail > 0) {
                "Export failed"
              } else {
                "Export completed"
              }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
          }
        }
      } catch (e: Exception) {
        Timber.e(e, "Unable to clear export state")
      } finally {
        withContext(Dispatchers.Main.immediate) {
          if (exports.isIdle) {
            totalSubmitted.set(0)
            completedCount.set(0)
            failedCount.set(0)
            stopSelf()
          }
        }
      }
    }
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    exports.cancelAll()
    httpClient.dispatcher.cancelAll()
    stopSelf()
  }

  override fun onDestroy() {
    activeExportJobs.clear()
    completedExportIds.clear()
    lastProgressWritten.clear()
    exports.cancelAll()
    httpClient.dispatcher.cancelAll()
    serviceScope.cancel()
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  private suspend fun addExportedSongId(songId: String, file: JSONObject) {
    dataStore.edit { preferences ->
      val current =
        preferences[ExportedSongIdsKey]
          .orEmpty()
          .split(',')
          .map { it.trim() }
          .filter { it.isNotBlank() }
      val updated = listOf(songId) + current.filterNot { it == songId }
      preferences[ExportFilesKey] =
        JSONObject(preferences[ExportFilesKey] ?: "{}").put(songId, file).toString()
      preferences[ExportedSongIdsKey] = updated.take(1000).joinToString(",")
    }
  }

  private suspend fun addExportingSongId(songId: String) {
    dataStore.edit { preferences ->
      val current =
        preferences[ExportingSongIdsKey]
          .orEmpty()
          .split(',')
          .map { it.trim() }
          .filter { it.isNotBlank() }
      val updated = listOf(songId) + current.filterNot { it == songId }
      preferences[ExportingSongIdsKey] = updated.take(1000).joinToString(",")
    }
  }

  private suspend fun removeExportingSongId(songId: String) {
    dataStore.edit { preferences ->
      val current =
        preferences[ExportingSongIdsKey]
          .orEmpty()
          .split(',')
          .map { it.trim() }
          .filter { it.isNotBlank() }
      preferences[ExportingSongIdsKey] = current.filterNot { it == songId }.joinToString(",")
    }
  }

  private suspend fun updateExportProgress(
    songId: String,
    percent: Int,
    name: String,
    stage: String,
  ) {
    val last = lastProgressWritten[songId] ?: -1
    if (percent == 100 || percent == 0 || kotlin.math.abs(percent - last) >= 10) {
      lastProgressWritten[songId] = percent
      dataStore.edit { it[ExportProgressKey] = "$songId:$percent:${Uri.encode(name)}:$stage" }
    }
  }

  companion object {
    private const val CHANNEL_ID = "music_export"
    private const val NOTIFICATION_ID = 889
    private const val ACTION_CANCEL_ALL = "echo.music.iad1tya.CANCEL_EXPORTS"
    private const val EXTRA_SONG_ID = "extra_song_id"
    private const val EXTRA_SONG_TITLE = "extra_song_title"
    private const val EXTRA_SONG_ARTIST = "extra_song_artist"
    private const val EXTRA_SONG_ALBUM = "extra_song_album"
    private const val EXTRA_ARTWORK_URL = "extra_artwork_url"
    private const val EXTRA_TARGET_DIRECTORY_URI = "extra_target_directory_uri"

    fun cancelAll(context: Context) {
      context.startService(
        Intent(context, AudioExportService::class.java).setAction(ACTION_CANCEL_ALL)
      )
    }

    fun start(
      context: Context,
      songId: String,
      songTitle: String,
      songArtist: String,
      songAlbum: String,
      artworkUrl: String,
      targetDirectoryUri: String,
    ) {
      val intent =
        Intent(context, AudioExportService::class.java).apply {
          putExtra(EXTRA_SONG_ID, songId)
          putExtra(EXTRA_SONG_TITLE, songTitle)
          putExtra(EXTRA_SONG_ARTIST, songArtist)
          putExtra(EXTRA_SONG_ALBUM, songAlbum)
          putExtra(EXTRA_ARTWORK_URL, artworkUrl)
          putExtra(EXTRA_TARGET_DIRECTORY_URI, targetDirectoryUri)
        }
      ContextCompat.startForegroundService(context, intent)
    }

    private fun sanitizeTitle(title: String): String =
      title.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim().ifBlank {
        "song_${System.currentTimeMillis()}"
      }
  }
}
