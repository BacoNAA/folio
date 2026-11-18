package com.baconaa.folio

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.*
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PlaceCoordinatesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val original = PlaceCoordinates(1.0, 2.0)
    private val proposed = PlaceCoordinates(30.67, 104.06)
    private var current: PlaceCoordinates? = original

    @Before fun allowLocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    private fun show(source: CurrentLocationSource) {
        compose.runOnUiThread {
            compose.activity.setContent {
                var value by remember { mutableStateOf(current) }
                MaterialTheme {
                    CompositionLocalProvider(LocalCurrentLocationSource provides source) {
                        PlaceCoordinatesField(value, true) { value = it; current = it }
                    }
                }
            }
        }
    }
    @Test fun suggestionRequiresConfirmationAndCanBeDiscardedOrCleared() {
        show { LocationFix(proposed, 1500f, true) }
        compose.onNodeWithText("获取当前位置").performClick()
        compose.onNodeWithText("系统提供的是大致位置。").assertExists()
        assertEquals(original, current)
        compose.onNodeWithText("暂不使用").performClick()
        assertEquals(original, current)
        compose.onNodeWithText("获取当前位置").performClick()
        compose.onNodeWithText("使用此坐标").performClick()
        assertEquals(proposed, current)
        compose.onNodeWithText("清除坐标").performClick()
        assertNull(current)
    }
    @Test fun failurePreservesCoordinatesAndRetryWorks() {
        var calls = 0
        show { if (calls++ == 0) error("定位暂不可用") else LocationFix(proposed, null, false) }
        compose.onNodeWithText("获取当前位置").performClick()
        compose.onNodeWithText("定位暂不可用").assertExists()
        assertEquals(original, current)
        compose.onNodeWithText("获取当前位置").performClick()
        compose.onNodeWithText("使用此坐标").performClick()
        assertEquals(proposed, current)
    }
    @Test fun cancelAndBackgroundStopTheRequestWithoutChangingCoordinates() {
        var cancellations = 0
        show { try { awaitCancellation() } finally { cancellations++ } }
        compose.onNodeWithText("获取当前位置").performClick()
        compose.onNodeWithText("取消获取位置").performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
        compose.onNodeWithText("获取当前位置").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(2, cancellations); assertEquals(original, current) }
        compose.onNodeWithText("获取当前位置").assertExists()
    }
}
