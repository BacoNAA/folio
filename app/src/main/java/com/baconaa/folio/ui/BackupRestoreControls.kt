package com.baconaa.folio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.baconaa.folio.records.BackupPreview
import com.baconaa.folio.records.LocalBackups
import com.baconaa.folio.records.preview
import com.baconaa.folio.records.restore
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun BackupRestoreControls(backups: LocalBackups, password: String, busy: Boolean, setJob: (Job?) -> Unit,
    message: (String) -> Unit, onRestored: () -> Unit, setCommitting: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var source by remember { mutableStateOf<Uri?>(null) }
    val value = preview
    DisposableEffect(value) {
        onDispose { value?.let { backups.release(it.directory) } }
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            preview = null
            source = uri
            message("正在核对备份版本、原件与本机资料…")
            setJob(scope.launch {
                try {
                    val secret = password.toCharArray()
                    preview = try { backups.preview(uri, secret) } finally { secret.fill(0.toChar()) }
                    message("")
                } catch (cancel: CancellationException) { message("已取消预检。"); throw cancel }
                catch (failure: Exception) { message("预检失败，请重新选择完整备份。\n${failure.localizedMessage.orEmpty()}") }
                finally { setJob(null) }
            })
        }
    }
    TextButton(onClick = { chooser.launch(arrayOf("*/*")) }, enabled = !busy && password.isNotEmpty()) { Text("从备份恢复") }
    if (value != null && source != null) {
        Text("备份 · ${DateFormat.getDateTimeInstance().format(Date(value.created))} · Folio ${value.version}\n${value.saved.label()}，媒体原件 ${value.originals.size} 个")
        Text("本机 · ${value.target.label()}。确认后，这台设备的记录、草稿、回收站、媒体与设置会被整份替换。")
        Button(enabled = !busy, onClick = {
            val uri = requireNotNull(source)
            setCommitting(true)
            message("正在重新核对并恢复，请稍候。")
            setJob(scope.launch {
                try {
                    val pending = backups.restore(value, uri)
                    val cleanup = if (pending == 0) "" else "\n旧原件待清理，可进入回收站重试。"
                    message("恢复完成。\n${value.saved.label()}$cleanup")
                    onRestored()
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { message("恢复失败。\n${failure.localizedMessage.orEmpty()}") }
                finally { preview = null; setCommitting(false); setJob(null) }
            })
        }) { Text("用此备份替换本机资料") }
        TextButton(enabled = !busy, onClick = { preview = null; message("已取消恢复。") }) { Text("保留本机资料") }
    }
}
