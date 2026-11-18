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
import com.baconaa.folio.records.DatePrecision
import com.baconaa.folio.records.EventTimeFields
import com.baconaa.folio.records.asFields
import java.time.LocalDate
import java.time.LocalTime
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
                it.readEventTime())
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
            target.eventTime?.let { writeEventTime(it) }
        }) }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        model.background()
        super.onStop()
    }
}

private fun Bundle.writeEventTime(value: EventTime) {
    val fields = value.asFields()
    putString("event-zone", fields.eventZone)
    fields.eventDate?.let { putString("event-date", LocalDate.ofEpochDay(it).toString()) }
    fields.eventClock?.let { putString("event-time", LocalTime.ofNanoOfDay(it).toString()) }
    putString("event-kind", fields.eventKind)
    fields.eventEndDate?.let { putLong("event-end", it) }
    putString("event-text", fields.eventText)
    putString("event-start-precision", fields.eventStartPrecision?.name)
    putString("event-end-precision", fields.eventEndPrecision?.name)
    fields.eventSeasonYear?.let { putInt("event-season-year", it) }
}

private fun Bundle.readEventTime(): EventTime? {
    val zone = getString("event-zone") ?: return null
    return EventTimeFields(
        eventDate = getString("event-date")?.let { LocalDate.parse(it).toEpochDay() },
        eventClock = getString("event-time")?.let { LocalTime.parse(it).toNanoOfDay() },
        eventZone = zone,
        eventKind = getString("event-kind"),
        eventEndDate = if (containsKey("event-end")) getLong("event-end") else null,
        eventText = getString("event-text"),
        eventStartPrecision = getString("event-start-precision")?.let(DatePrecision::valueOf),
        eventEndPrecision = getString("event-end-precision")?.let(DatePrecision::valueOf),
        eventSeasonYear = if (containsKey("event-season-year")) getInt("event-season-year") else null,
    ).asEventTime()
}
