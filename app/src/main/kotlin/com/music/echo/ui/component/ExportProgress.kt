package echo.music.iad1tya.ui.component

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun ExportProgress(raw: String, songId: String, fallbackTitle: String) {
  val parts = raw.split(":")
  val matches = parts.firstOrNull() == songId
  val percent = if (matches) parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) else null
  val title = if (matches) parts.getOrNull(2)?.let(Uri::decode).orEmpty().ifBlank { fallbackTitle } else fallbackTitle
  val stage = if (matches) parts.getOrNull(3).orEmpty() else "Waiting"
  val bytes = if (matches) parts.getOrNull(4)?.toLongOrNull() ?: 0 else 0
  val total = if (matches) parts.getOrNull(5)?.toLongOrNull() ?: -1 else -1
  Column {
    Text("Exporting: $title")
    Text("$stage${percent?.let { "… $it%" } ?: "…"}")
    if (stage == "Downloading" && bytes > 0) {
      val size = String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
      val totalSize = if (total > 0) " / " + String.format(Locale.getDefault(), "%.1f MB", total / 1048576.0) else ""
      Text(size + totalSize)
    }
    if (percent != null) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
  }
}
