package com.baconaa.folio.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.ContentItem
import com.baconaa.folio.records.InvalidLinkAddress
import com.baconaa.folio.records.webAddress

@Composable
internal fun LinkEditor(item: ContentItem.Link, enabled: Boolean, canChoose: Boolean, invalid: InvalidLinkAddress?,
    onChange: (ContentItem.Link) -> Unit, onScreenshot: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(invalid, enabled) { if (invalid != null && enabled) focus.requestFocus() }
    OutlinedTextField(item.address, { onChange(item.copy(address = it)) }, singleLine = true, enabled = enabled,
        modifier = Modifier.fillMaxWidth().focusRequester(focus), label = { Text("网址地址") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = invalid != null,
        supportingText = invalid?.let { { Text(it.message.orEmpty()) } })
    OutlinedTextField(item.title, { onChange(item.copy(title = it)) }, singleLine = true, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), label = { Text("链接标题（选填）") })
    OutlinedTextField(item.note, { onChange(item.copy(note = it)) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), label = { Text("链接备注（选填）") })
    item.screenshotId?.let { Photo(it) }
    Row {
        TextButton(onClick = onScreenshot, enabled = canChoose) { Text(if (item.screenshotId == null) "选择截图" else "更换截图") }
        if (item.screenshotId != null) TextButton(onClick = { onChange(item.copy(screenshotId = null)) }, enabled = canChoose) { Text("移除截图") }
    }
}

@Composable
internal fun SavedLink(item: ContentItem.Link, bodySize: TextUnit, lineHeight: TextUnit) {
    val context = LocalContext.current
    var openError by remember(item.address) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.title.isNotBlank()) Text(item.title, style = MaterialTheme.typography.titleMedium)
        Text(item.address, fontSize = bodySize, lineHeight = lineHeight)
        if (item.note.isNotBlank()) Text(item.note, fontSize = bodySize, lineHeight = lineHeight)
        item.screenshotId?.let { Photo(it) }
        Row {
            TextButton(onClick = {
                val url = webAddress(item.address)
                if (url == null) {
                    openError = "请编辑记录并补全网址。"
                } else try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                    openError = null
                } catch (_: ActivityNotFoundException) {
                    openError = "请安装浏览器后重试，或复制网址。"
                } catch (_: SecurityException) {
                    openError = "系统未允许打开浏览器，可复制网址。"
                }
            }) { Text("打开网址") }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("网址", item.address))
            }) { Text("复制网址") }
        }
        openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
