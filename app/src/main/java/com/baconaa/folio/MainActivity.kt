package com.baconaa.folio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                FolioHome()
            }
        }
    }
}

private enum class HomeTab(val title: Int, val status: Int) {
    QuickNote(R.string.quick_note, R.string.quick_note_status),
    Records(R.string.records, R.string.records_status),
}

@Composable
private fun FolioHome() {
    var currentTab by rememberSaveable { mutableStateOf(HomeTab.QuickNote) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.headlineMedium,
            )
            PrimaryTabRow(selectedTabIndex = currentTab.ordinal) {
                for (tab in HomeTab.entries) {
                    Tab(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        text = { Text(stringResource(tab.title)) },
                    )
                }
            }
            Text(
                text = stringResource(currentTab.status),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
