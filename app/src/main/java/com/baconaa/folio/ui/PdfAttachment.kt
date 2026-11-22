package com.baconaa.folio.ui

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.baconaa.folio.records.ContentItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PdfAttachment(item: ContentItem.Pdf, model: FolioViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember(item.id) { mutableStateOf(false) }
    var error by rememberSaveable(item.id.value) { mutableStateOf<String?>(null) }
    Column {
        Text(item.displayName)
        Text(Formatter.formatFileSize(context, item.sizeBytes), style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !opening, modifier = Modifier.semantics { contentDescription = "打开 ${item.displayName}" },
            onClick = {
                opening = true
                error = null
                scope.launch {
                    try {
                        context.startActivity(model.pdfViewIntent(item))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: ActivityNotFoundException) {
                        error = "本机没有可打开 PDF 的应用。安装查看器后可再点「打开 PDF」。"
                    } catch (_: Exception) {
                        error = "暂时无法打开这份 PDF，请重试。"
                    } finally {
                        opening = false
                    }
                }
            }) { Text("打开 PDF") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
