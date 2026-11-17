package com.baconaa.folio.records

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.baconaa.folio.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule

class CurrentLocationTest {
    // 定位权限仅限前台；平台验收与实际入口一样保持 Activity 可见。
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Suppress("DEPRECATION")
    @Test fun platformRequestReturnsFreshFixAndCanBeCancelled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        fun shell(command: String) { automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        shell("appops set ${context.packageName} android:mock_location allow")
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = LocationManager.GPS_PROVIDER
        try {
            manager.addTestProvider(provider, false, false, false, false, true, true, true, 1, 1)
            manager.setTestProviderEnabled(provider, true)
            val source = AndroidCurrentLocation(context)
            withContext(Dispatchers.Main) {
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { source.locate() }
                cancelled.cancelAndJoin()
                assertTrue(cancelled.isCancelled)
                val result = async(start = CoroutineStart.UNDISPATCHED) { source.locate() }
                val fix = Location(provider).apply {
                    latitude = 30.67; longitude = 104.06; accuracy = 7f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                manager.setTestProviderLocation(provider, fix)
                val received = withTimeout(10_000) { result.await() }
                assertEquals(PlaceCoordinates(30.67, 104.06), received.coordinates)
                assertEquals(7f, received.accuracyMeters)
                assertFalse(received.approximate)
            }
        } finally {
            manager.removeTestProvider(provider)
            shell("appops set ${context.packageName} android:mock_location default")
        }
    }
}
