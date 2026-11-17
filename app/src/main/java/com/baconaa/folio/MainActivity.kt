package com.baconaa.folio

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.baconaa.folio.records.ItemId
import com.baconaa.folio.records.LocalTextStore
import com.baconaa.folio.records.EventTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import com.baconaa.folio.ui.*
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var model: FolioViewModel
    private var pendingTarget: ExternalTarget? = null
    private var cameraPath: String? = null

    private val gallery = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = pendingTarget
        pendingTarget = null
        if (uri != null && target != null) {
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* 部分旧系统只提供临时读取授权，立即复制原件。 */ }
            model.receivePhoto(uri, target)
        }
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val target = pendingTarget
        val file = cameraPath?.let(::File)
        pendingTarget = null
        cameraPath = null
        if (success && target != null && file != null) model.receivePhoto(Uri.fromFile(file), target)
        else file?.delete()
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pendingTarget
        pendingTarget = null
        if (granted && target != null) model.startRecording(target) else model.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        savedInstanceState?.getBundle("media-target")?.let {
            pendingTarget = ExternalTarget(it.getString("draft"), it.getString("record"), it.getString("after"),
                Page.valueOf(it.getString("return") ?: "Capture"), Page.valueOf(it.getString("origin") ?: "Records"),
                it.getString("event-date")?.let { date -> EventTime(LocalDate.parse(date),
                    it.getString("event-time")?.let(LocalTime::parse), ZoneId.of(it.getString("event-zone"))) })
            cameraPath = it.getString("camera")
        }
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val store = LocalTextStore.get(applicationContext)
                return FolioViewModel(store, store, store, store, LocalEditorMedia(applicationContext)) as T
            }
        })[FolioViewModel::class.java]
        setContent { FolioApp(model, ::launchMedia) }
    }

    private fun launchMedia(kind: String, after: ItemId?) {
        model.prepareExternal(after, currentTime = kind != "photo") { target ->
            pendingTarget = target
            try {
                when (kind) {
                    "photo" -> gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    "camera" -> {
                        val folder = File(cacheDir, "camera").apply { mkdirs() }
                        val file = File.createTempFile("capture-", ".jpg", folder)
                        cameraPath = file.absolutePath
                        camera.launch(FileProvider.getUriForFile(this, "$packageName.camera", file))
                    }
                    "audio" -> microphone.launch(Manifest.permission.RECORD_AUDIO)
                }
            } catch (_: Exception) {
                pendingTarget = null
                cameraPath?.let { File(it).delete() }
                cameraPath = null
                model.mediaUnavailable("无法打开设备入口，请检查是否有可用的相机或选图应用；仍可继续编辑。")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingTarget?.let { target -> outState.putBundle("media-target", Bundle().apply {
            putString("draft", target.draftId); putString("record", target.recordId); putString("after", target.afterId)
            putString("return", target.returnPage.name); putString("origin", target.detailOrigin.name); putString("camera", cameraPath)
            putString("event-date", target.eventTime?.date?.toString())
            putString("event-time", target.eventTime?.time?.toString())
            putString("event-zone", target.eventTime?.zone?.id)
        }) }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        model.background()
        super.onStop()
    }
}
