package echo.music.iad1tya.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import echo.music.iad1tya.LocalDatabase
import echo.music.iad1tya.LocalPlayerAwareWindowInsets
import echo.music.iad1tya.LocalPlayerConnection
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.EnableExportAsMp3Key
import echo.music.iad1tya.constants.ExportDirectoryUriKey
import echo.music.iad1tya.constants.ExportProgressKey
import echo.music.iad1tya.constants.ExportingSongIdsKey
import echo.music.iad1tya.constants.MaxImageCacheSizeKey
import echo.music.iad1tya.constants.MaxSongCacheSizeKey
import echo.music.iad1tya.extensions.tryOrNull
import echo.music.iad1tya.playback.AudioExportService
import echo.music.iad1tya.ui.component.ActionPromptDialog
import echo.music.iad1tya.ui.component.IconButton
import echo.music.iad1tya.ui.component.Material3SettingsGroup
import echo.music.iad1tya.ui.component.Material3SettingsItem
import echo.music.iad1tya.ui.utils.backToMain
import echo.music.iad1tya.ui.utils.formatFileSize
import echo.music.iad1tya.utils.launchSafely
import echo.music.iad1tya.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import timber.log.Timber

@OptIn(ExperimentalCoilApi::class, ExperimentalMaterial3Api::class, DelicateCoilApi::class)
@Composable
fun StorageSettings(
  navController: NavController,
  scrollBehavior: TopAppBarScrollBehavior,
  autoOpenExportPicker: Boolean = false,
  highlightKey: String? = null,
) {
  val scrollState = rememberScrollState()

  val context = LocalContext.current
  val database = LocalDatabase.current
  val imageDiskCache = context.imageLoader.diskCache ?: return
  val playerCache = LocalPlayerConnection.current?.service?.playerCache ?: return
  val downloadCache = LocalPlayerConnection.current?.service?.downloadCache ?: return

  val coroutineScope = rememberCoroutineScope()
  val (maxImageCacheSize) = rememberPreference(key = MaxImageCacheSizeKey, defaultValue = 512)
  val (maxSongCacheSize) = rememberPreference(key = MaxSongCacheSizeKey, defaultValue = 1024)
  val (exportDirectoryUri, onExportDirectoryUriChange) =
    rememberPreference(
      key = ExportDirectoryUriKey,
      defaultValue = "",
    )
  val (enableExportAsMp3, onEnableExportAsMp3Change) =
    rememberPreference(key = EnableExportAsMp3Key, defaultValue = false)

  val exportingSongIds by rememberPreference(ExportingSongIdsKey, "")
  val (exportProgressRaw) = rememberPreference(key = ExportProgressKey, defaultValue = "")

  val exportDirectoryLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        try {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )
        } catch (e: SecurityException) {
          Timber.w(e, "Unable to persist export directory permission")
          Toast.makeText(context, R.string.system_picker_unavailable, Toast.LENGTH_LONG).show()
          return@rememberLauncherForActivityResult
        }
        onExportDirectoryUriChange(uri.toString())
      }
    }

  val pickerNotAvailableMsg = stringResource(R.string.picker_not_available)
  val exportDirUnavailableMsg = stringResource(R.string.export_directory_unavailable)

  val launchExportPicker: () -> Unit = {
    exportDirectoryLauncher.launchSafely(null) {
      val fallbackDir = context.getExternalFilesDir(null)
      if (fallbackDir != null) {
        onExportDirectoryUriChange(fallbackDir.toUri().toString())
        Toast.makeText(context, pickerNotAvailableMsg, Toast.LENGTH_LONG).show()
      } else {
        Timber.e("getExternalFilesDir returned null after picker launch failure")
        Toast.makeText(context, exportDirUnavailableMsg, Toast.LENGTH_LONG).show()
      }
    }
  }

  var exportPickerAutoOpened by remember { mutableStateOf(false) }
  LaunchedEffect(autoOpenExportPicker) {
    if (autoOpenExportPicker && !exportPickerAutoOpened) {
      exportPickerAutoOpened = true
      launchExportPicker()
    }
  }

  var clearDownloads by remember { mutableStateOf(false) }
  var clearCacheDialog by remember { mutableStateOf(false) }
  var clearImageCacheDialog by remember { mutableStateOf(false) }

  var imageCacheSize by remember { mutableLongStateOf(imageDiskCache.size) }
  var playerCacheSize by remember {
    mutableLongStateOf(tryOrNull { playerCache.cacheSpace } ?: 0)
  }
  var downloadCacheSize by remember {
    mutableLongStateOf(tryOrNull { downloadCache.cacheSpace } ?: 0)
  }

  LaunchedEffect(maxImageCacheSize) {
    SingletonImageLoader.reset()
    if (maxImageCacheSize == 0) {
      coroutineScope.launch(Dispatchers.IO) { imageDiskCache.clear() }
    }
  }
  LaunchedEffect(maxSongCacheSize) {
    if (maxSongCacheSize == 0) {
      coroutineScope.launch(Dispatchers.IO) {
        playerCache.keys.forEach { key -> playerCache.removeResource(key) }
      }
    }
  }

  LaunchedEffect(imageDiskCache) {
    while (isActive) {
      delay(500)
      imageCacheSize = imageDiskCache.size
    }
  }
  LaunchedEffect(playerCache) {
    while (isActive) {
      delay(500)
      playerCacheSize = tryOrNull { playerCache.cacheSpace } ?: 0
    }
  }
  LaunchedEffect(downloadCache) {
    while (isActive) {
      delay(500)
      downloadCacheSize = tryOrNull { downloadCache.cacheSpace } ?: 0
    }
  }

  if (clearDownloads) {
    ActionPromptDialog(
      title = stringResource(R.string.clear_all_downloads),
      onDismiss = { clearDownloads = false },
      onConfirm = {
        androidx.media3.exoplayer.offline.DownloadService.sendRemoveAllDownloads(
          context,
          echo.music.iad1tya.playback.ExoDownloadService::class.java,
          false
        )
        clearDownloads = false
      },
      onCancel = { clearDownloads = false },
      content = { Text(text = stringResource(R.string.clear_downloads_dialog)) }
    )
  }

  if (clearCacheDialog) {
    ActionPromptDialog(
      title = stringResource(R.string.clear_song_cache),
      onDismiss = { clearCacheDialog = false },
      onConfirm = {
        coroutineScope.launch(Dispatchers.IO) {
          playerCache.keys.forEach { key -> playerCache.removeResource(key) }
        }
        clearCacheDialog = false
      },
      onCancel = { clearCacheDialog = false },
      content = { Text(text = stringResource(R.string.clear_song_cache_dialog)) }
    )
  }

  if (clearImageCacheDialog) {
    ActionPromptDialog(
      title = stringResource(R.string.clear_image_cache),
      onDismiss = { clearImageCacheDialog = false },
      onConfirm = {
        coroutineScope.launch(Dispatchers.IO) {
          val urlsToPreserve = mutableSetOf<String>()
          val downloadedSongs =
            try {
              database.downloadedSongsByNameAsc().first()
            } catch (e: Exception) {
              emptyList()
            }
          downloadedSongs.forEach { song ->
            song.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
            song.album?.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
          }
          val directory = imageDiskCache.directory.toFile()
          if (directory.exists() && directory.isDirectory) {
            directory.listFiles()?.forEach { file ->
              if (file.isFile && !file.name.startsWith("journal")) {
                val isPreserved = urlsToPreserve.any { hash -> file.name.startsWith(hash) }
                if (!isPreserved) {
                  file.delete()
                }
              }
            }
          }
        }
        clearImageCacheDialog = false
      },
      onCancel = { clearImageCacheDialog = false },
      content = { Text(text = stringResource(R.string.clear_image_cache_dialog)) }
    )
  }

  Column(
    Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal)
      )
      .verticalScroll(scrollState)
      .padding(horizontal = 16.dp)
  ) {
    Spacer(
      Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top))
    )

    Spacer(modifier = Modifier.height(16.dp))

    Material3SettingsGroup(
      scrollState = scrollState,
      title = stringResource(R.string.storage),
      items =
        listOf(
          Material3SettingsItem(
            isHighlighted = (highlightKey == stringResource(R.string.downloaded_songs)),
            icon = painterResource(R.drawable.storage),
            title = { Text(stringResource(R.string.downloaded_songs)) },
            description = { Text(text = formatFileSize(downloadCacheSize)) },
          ),
          Material3SettingsItem(
            isHighlighted = (highlightKey == stringResource(R.string.clear_all_downloads)),
            icon = painterResource(R.drawable.clear_all),
            title = { Text(stringResource(R.string.clear_all_downloads)) },
            description = { Text(stringResource(R.string.clear_all_downloads_desc)) },
            onClick = { clearDownloads = true },
          ),
          Material3SettingsItem(
            isHighlighted = (highlightKey == stringResource(R.string.export_directory)),
            icon = painterResource(R.drawable.folder_managed),
            title = { Text(stringResource(R.string.export_directory)) },
            description = {
              Text(
                text =
                  if (exportDirectoryUri.isBlank()) {
                    stringResource(R.string.export_directory_not_set)
                  } else {
                    exportDirectoryUri
                  }
              )
            },
            onClick = { launchExportPicker() },
          ),
          Material3SettingsItem(
            icon = painterResource(R.drawable.file_export),
            title = { Text(stringResource(R.string.export_all_songs)) },
            description = { Text(stringResource(R.string.export_music)) },
            onClick = {
              if (exportDirectoryUri.isBlank()) {
                Toast.makeText(context, R.string.export_directory_not_set, Toast.LENGTH_SHORT).show()
              } else {
                coroutineScope.launch(Dispatchers.IO) {
                  val downloadedSongs = database.downloadedSongsByNameAsc().first()
                  if (downloadedSongs.isEmpty()) {
                    withContext(Dispatchers.Main) {
                      Toast.makeText(context, R.string.playlist_is_empty, Toast.LENGTH_SHORT).show()
                    }
                  } else {
                    downloadedSongs.forEach { song ->
                      AudioExportService.start(
                        context = context,
                        songId = song.id,
                        songTitle = song.song.title,
                        songArtist = song.artists.joinToString(", ") { it.name },
                        songAlbum = song.album?.title ?: song.song.albumName ?: "",
                        artworkUrl = song.thumbnailUrl ?: "",
                        targetDirectoryUri = exportDirectoryUri,
                      )
                    }
                    withContext(Dispatchers.Main) {
                      Toast.makeText(
                        context,
                        "Exporting ${downloadedSongs.size} songs",
                        Toast.LENGTH_SHORT
                      ).show()
                    }
                  }
                }
              }
            },
          ),
          Material3SettingsItem(
            icon = painterResource(R.drawable.music_note),
            title = { Text(stringResource(R.string.export_desc)) },
            trailingContent = {
              Switch(
                checked = enableExportAsMp3,
                onCheckedChange = { onEnableExportAsMp3Change(it) },
              )
            },
            onClick = { onEnableExportAsMp3Change(!enableExportAsMp3) },
          ),
        ),
    )

    val progress = remember(exportProgressRaw) { exportProgressRaw.split(':', limit = 4) }
    if (progress.size == 4) {
      val percent = progress[1].toIntOrNull() ?: 0
      Spacer(modifier = Modifier.height(16.dp))
      Material3SettingsGroup(
        scrollState = scrollState,
        title = stringResource(R.string.exporting),
        items =
          listOf(
            Material3SettingsItem(
              icon = painterResource(R.drawable.file_export),
              title = { Text(android.net.Uri.decode(progress[2])) },
              description = {
                Column {
                  Text("${progress[3]} · $percent%")
                  androidx.compose.material3.LinearProgressIndicator(progress = { percent / 100f })
                }
              },
            ),
            *if (exportingSongIds.isNotBlank()) arrayOf(Material3SettingsItem(
              icon = painterResource(R.drawable.close),
              title = { Text(stringResource(R.string.cancel_exports)) },
              onClick = { AudioExportService.cancelAll(context) },
            )) else emptyArray(),
          ),
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Material3SettingsGroup(
      scrollState = scrollState,
      title = stringResource(R.string.song_cache),
      items =
        listOf(
          Material3SettingsItem(
            isHighlighted = (highlightKey == stringResource(R.string.clear_song_cache)),
            icon = painterResource(R.drawable.cached),
            title = { Text(stringResource(R.string.clear_song_cache)) },
            description = { Text("${formatFileSize(playerCacheSize)} used") },
            onClick = { clearCacheDialog = true },
          )
        ),
    )

    Spacer(modifier = Modifier.height(16.dp))

    Material3SettingsGroup(
      scrollState = scrollState,
      title = stringResource(R.string.image_cache),
      items =
        listOf(
          Material3SettingsItem(
            isHighlighted = (highlightKey == stringResource(R.string.clear_image_cache)),
            icon = painterResource(R.drawable.image),
            title = { Text(stringResource(R.string.clear_image_cache)) },
            description = { Text("${formatFileSize(imageCacheSize)} used") },
            onClick = { clearImageCacheDialog = true },
          )
        ),
    )
    Spacer(Modifier.padding(bottom = 30.dp))

    Spacer(
      Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
      )
    )
  }

  TopAppBar(
    title = { Text(stringResource(R.string.storage)) },
    navigationIcon = {
      IconButton(
        onClick = navController::navigateUp,
        onLongClick = navController::backToMain,
      ) {
        Icon(
          painterResource(R.drawable.arrow_back),
          contentDescription = null,
        )
      }
    },
  )
}
