package com.baconaa.folio.records

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class LocationFix(val coordinates: PlaceCoordinates, val accuracyMeters: Float?, val approximate: Boolean)
fun interface CurrentLocationSource { suspend fun locate(): LocationFix }

/** 单次前台请求；不使用上次位置，也不自动写入地点资料。 */
class AndroidCurrentLocation(context: Context) : CurrentLocationSource {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission")
    override suspend fun locate(): LocationFix = withContext(Dispatchers.Main.immediate) {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        check(fine || coarse) { "未获得定位权限，可以继续保存无坐标地点。" }
        check(manager.isLocationEnabled) { "系统定位未开启；开启后可重试，也可以不填坐标。" }
        val providers = manager.getProviders(true).filter {
            it == LocationManager.NETWORK_PROVIDER || (it == LocationManager.GPS_PROVIDER && (fine || Build.VERSION.SDK_INT >= 31))
        }
        check(providers.isNotEmpty()) { "当前没有可用的定位来源，可稍后重试。" }
        withTimeoutOrNull(20_000) {
            var listener: LocationListener? = null
            try {
                suspendCancellableCoroutine { continuation ->
                    listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
                            if (!continuation.isActive || age !in 0..60_000_000_000L) return
                            val coordinates = runCatching { PlaceCoordinates(location.latitude, location.longitude) }.getOrNull() ?: return
                            continuation.resume(LocationFix(coordinates,
                                location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() && it >= 0 }, !fine))
                        }
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                        @Deprecated("Platform compatibility")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    }
                    providers.forEach { manager.requestLocationUpdates(it, 0L, 0f, listener!!, Looper.getMainLooper()) }
                }
            } finally {
                listener?.let(manager::removeUpdates)
            }
        } ?: error("暂时没有获取到当前位置，请到信号较好的地方重试；也可以不填坐标。")
    }
}
