package com.baconaa.folio.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.baconaa.folio.records.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

internal val LocalCurrentLocationSource = staticCompositionLocalOf<CurrentLocationSource?> { null }
internal fun PlaceCoordinates.label() = String.format(Locale.ROOT, "纬度 %.5f，经度 %.5f", latitude, longitude)

@Composable
internal fun PlaceCoordinatesField(value: PlaceCoordinates?, enabled: Boolean, change: (PlaceCoordinates?) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val platform = remember(context) { AndroidCurrentLocation(context) }
    val source = LocalCurrentLocationSource.current ?: platform
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var locating by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<LocationFix?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentEnabled by rememberUpdatedState(enabled)
    fun cancel() { job?.cancel(); job = null; locating = false; proposal = null }
    fun locate() {
        if (!currentEnabled || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        cancel()
        error = null
        locating = true
        job = scope.launch {
            try { proposal = source.locate() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { error = "未获得定位权限，可以继续保存无坐标地点。" }
            catch (failure: Exception) { error = failure.message ?: "获取位置失败，请重试。" }
            finally { locating = false }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate() else error = "未获得定位权限，可以继续保存无坐标地点。"
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel() }
    }
    LaunchedEffect(enabled) { if (!enabled) cancel() }
    Column {
        Text(value?.label() ?: "坐标（选填）")
        Text("仅在你主动获取时定位。当前位置不一定是这段经历的发生地，请核对后使用。", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = enabled, onClick = {
            if (locating) cancel()
            else if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
            else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }) { Text(if (locating) "取消获取位置" else "获取当前位置") }
        if (value != null) TextButton(enabled = enabled, onClick = { cancel(); change(null) }) { Text("清除坐标") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    proposal?.let { fix ->
        AlertDialog(onDismissRequest = { proposal = null }, title = { Text("确认地点坐标") }, text = {
            Column {
                Text(fix.coordinates.label())
                Text(if (fix.approximate) "系统提供的是大致位置。" else "系统提供的当前位置。")
                Text(fix.accuracyMeters?.let { "估计误差约 ${kotlin.math.ceil(it.toDouble()).toLong()} 米；请确认是否适合这个地点。" } ?: "系统未提供误差范围，请核对后使用。")
                Text("使用后仍需保存地点；不会替换地点名称、城市或地址。")
            }
        }, confirmButton = { TextButton(enabled = enabled, onClick = { change(fix.coordinates); proposal = null }) { Text("使用此坐标") } },
            dismissButton = { TextButton(onClick = { proposal = null }) { Text("暂不使用") } })
    }
}
