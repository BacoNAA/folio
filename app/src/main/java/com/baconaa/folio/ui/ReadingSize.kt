package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.ReadingSettings
import kotlinx.coroutines.launch

@Composable
internal fun ReadingBody(content: @Composable (enlarged: Boolean) -> Unit) {
    val app = LocalContext.current.applicationContext
    val settings = remember(app) { ReadingSettings(app) }
    var enlarged by remember { mutableStateOf<Boolean?>(null) }
    var readFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var open by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settings, attempt) {
        enlarged = settings.readEnlargedBody()
        readFailed = enlarged == null
    }
    TextButton(onClick = { saveFailed = false; open = true }, enabled = enlarged != null) { Text("阅读设置") }
    if (readFailed) ErrorMessage("阅读设置读取失败。") { attempt++ }
    content(enlarged == true)

    if (open && enlarged != null) AlertDialog(
        onDismissRequest = { if (!saving) open = false },
        title = { Text("阅读字号") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                for ((large, label) in listOf(false to "跟随系统", true to "放大正文")) {
                    Row(Modifier.fillMaxWidth().selectable(enlarged == large, enabled = !saving, role = Role.RadioButton) {
                        if (large == enlarged) open = false
                        else {
                            saving = true
                            scope.launch {
                                saveFailed = !settings.saveEnlargedBody(large)
                                if (!saveFailed) {
                                    enlarged = large
                                    open = false
                                }
                                saving = false
                            }
                        }
                    }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = enlarged == large, onClick = null, enabled = !saving)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
                if (saveFailed) Text("字号未保存，请重新选择。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { open = false }, enabled = !saving) { Text("关闭") } },
    )
}
