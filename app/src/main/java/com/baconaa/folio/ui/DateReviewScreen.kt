package com.baconaa.folio.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.NamedSeason
import java.time.LocalDate

@Composable
internal fun DateReviewScreen(model: FolioViewModel) {
    val state = model.state
    val query = state.reviewQuery
    val context = LocalContext.current
    var showExcluded by rememberSaveable(query.season) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("date-review"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedButton(onClick = {
                DatePickerDialog(context, { _, year, month, day ->
                    model.reviewDates(query.copy(date = LocalDate.of(year, month + 1, day)))
                }, query.date.year, query.date.monthValue - 1, query.date.dayOfMonth).show()
            }) { Text("参照日期 · ${query.date}") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(query.season == null, { model.reviewDates(query.copy(season = null)) }, { Text("历年同日") })
                NamedSeason.entries.forEach { season ->
                    FilterChip(query.season == season, { model.reviewDates(query.copy(season = season)) }, { Text(season.label) })
                }
            }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!state.loading && state.error == null) {
            val matches = state.reviewMatches
            val hasExcluded = matches.any { it.excluded }
            if (query.season != null && hasExcluded) item {
                TextButton(onClick = { showExcluded = !showExcluded }) {
                    Text(if (showExcluded) "返回季节回看" else "管理本季已排除记录")
                }
            }
            val visible = matches.filter { it.excluded == (showExcluded && hasExcluded) }
            if (query.season != null && visible.isEmpty()) item { Text("这个季节暂无可回看的记录。") }
            items(visible, key = { it.record.id.value }) { match ->
                Text(match.reason, style = MaterialTheme.typography.labelLarge)
                Text(requireNotNull(match.record.eventTime).label)
                RecordPreview(match.record, model) {
                    if (query.season != null) TextButton(enabled = !state.busy,
                        onClick = { model.setSeasonExcluded(match.record.id, !match.excluded) }) {
                        Text(if (match.excluded) "恢复季节回看" else "从季节回看排除")
                    }
                }
            }
        }
    }
}
