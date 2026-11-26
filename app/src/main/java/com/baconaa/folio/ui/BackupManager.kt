package com.baconaa.folio.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.baconaa.folio.records.LocalTextStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun BackupManager() {
    val context = LocalContext.current
    val backups = remember(context) { LocalTextStore.get(context).backups }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<Uri?>(null) }
    val directory = rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: android.content.Context, input: Uri?) =
            super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri ->
        if (uri != null) {
            status = "正在准备数据并写入新文件…"
            saved = null
            job = scope.launch {
                try {
                    val secret = password.toCharArray()
                    val result = try { backups.export(uri, secret) } finally { secret.fill(0.toChar()) }
                    password = ""; confirmation = ""
                    saved = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, android.provider.DocumentsContract.getTreeDocumentId(uri))
                    status = "备份已保存 · ${result.name}\n${result.summary.label()}\n${android.provider.DocumentsContract.getDocumentId(result.uri)}"
                } catch (cancel: CancellationException) { status = "已取消创建备份。"; throw cancel }
                catch (failure: Exception) { status = "备份失败。请检查空间和目录权限后重试。\n${failure.localizedMessage.orEmpty()}" }
                finally { job = null }
            }
        }
    }
    TextButton(onClick = { open = true }) { Text("数据管理") }
    if (open) AlertDialog(onDismissRequest = { if (job == null) { open = false; password = ""; confirmation = "" } },
        title = { Text("数据管理") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("完整备份包含记录、草稿、回收站、设置和媒体原件。")
                Text("使用密码保护文件。恢复时需要同一密码，Folio 无法找回密码。")
                OutlinedTextField(password, { password = it }, modifier = Modifier.testTag("backup-password"), label = { Text("备份密码") }, singleLine = true,
                    enabled = job == null, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                OutlinedTextField(confirmation, { confirmation = it }, modifier = Modifier.testTag("backup-confirmation"), label = { Text("再输入一次导出密码") }, singleLine = true,
                    enabled = job == null, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Text("创建备份需要至少八个字符，两次输入须相同。")
                Button(onClick = { directory.launch(null) }, enabled = job == null && password.length >= 8 && confirmation == password) { Text("创建完整备份") }
                if (job != null) {
                    LinearProgressIndicator()
                    TextButton(onClick = { job?.cancel() }) { Text("取消操作") }
                }
                if (status.isNotEmpty()) Text(status)
                saved?.let { file ->
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(file, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: Exception) { status = "请在系统文件应用的所选目录中查看备份。" }
                    }) { Text("打开备份所在目录") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false; password = ""; confirmation = "" }, enabled = job == null) { Text("关闭") } })
}
