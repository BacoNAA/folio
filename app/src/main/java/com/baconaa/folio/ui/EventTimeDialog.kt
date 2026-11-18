package com.baconaa.folio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Precision(val label: String) {
    EXACT("具体日期"), MONTH("月份"), SEASON("季节"), YEAR("年份"), RANGE("日期范围"), DESCRIPTION("自己的说法")
}

@Composable
private fun <T> Choice(label: String, options: List<T>, optionLabel: (T) -> String, choose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { expanded = false; choose(option) })
            }
        }
    }
}

@Composable
private fun TimeField(value: String, change: (String) -> Unit, label: String, tag: String) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
internal fun EventTimeDialog(model: FolioViewModel, value: EventTime?, onDismiss: () -> Unit,
    seasonOnly: Boolean = false, save: (EventTime?) -> Unit) {
    val exact = value as? EventTime.Exact
    val originalSeason = value as? EventTime.Season
    var kind by rememberSaveable { mutableStateOf(when {
        seasonOnly || value is EventTime.Season -> Precision.SEASON
        value is EventTime.Year -> Precision.YEAR
        value is EventTime.Month -> Precision.MONTH
        value is EventTime.Range -> Precision.RANGE
        value is EventTime.Description -> Precision.DESCRIPTION
        else -> Precision.EXACT
    }) }
    var date by rememberSaveable { mutableStateOf(exact?.date?.toString().orEmpty()) }
    var time by rememberSaveable { mutableStateOf(exact?.time?.toString().orEmpty()) }
    var zone by rememberSaveable { mutableStateOf(value?.zone?.id ?: ZoneId.systemDefault().id) }
    var year by rememberSaveable { mutableStateOf(value?.groupingYear?.toString().orEmpty()) }
    var month by rememberSaveable { mutableStateOf((value as? EventTime.Month)?.month?.toString().orEmpty()) }
    var season by rememberSaveable { mutableStateOf(originalSeason?.season ?: NamedSeason.SPRING) }
    var useCurrentMonths by rememberSaveable { mutableStateOf(originalSeason == null) }
    val originalInputs = when (value) {
        is EventTime.Range -> value.precision.inputs(value.dateRange)
        is EventTime.Description -> value.dateRange?.let(value.precision::inputs)
        else -> null
    }
    var start by rememberSaveable { mutableStateOf(originalInputs?.first.orEmpty()) }
    var end by rememberSaveable { mutableStateOf(originalInputs?.second.orEmpty()) }
    var description by rememberSaveable { mutableStateOf((value as? EventTime.Description)?.text.orEmpty()) }
    var settings by remember { mutableStateOf<Map<NamedSeason, SeasonMonths>?>(null) }
    var readFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var editMonths by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(kind, reload) {
        if (kind == Precision.SEASON) {
            readFailed = false
            try { settings = model.seasonMonths() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { readFailed = true }
        }
    }
    val keepOriginal = originalSeason != null && originalSeason.season == season && !useCurrentMonths
    val months = if (keepOriginal) SeasonMonths(originalSeason.firstMonth, originalSeason.lastMonth) else settings?.get(season)
    val seasonYear = year.trim().toIntOrNull()?.takeIf { it in 1000..9999 }
    val selectedSeason = if (seasonYear != null && months != null) EventTime.Season(seasonYear, season,
        months.firstMonth, months.lastMonth, value?.zone ?: ZoneId.systemDefault()).takeIf { it.dateRange.endInclusive.year <= 9999 } else null

    if (editMonths) {
        settings?.get(season)?.let { current ->
            SeasonMonthsDialog(model, season, current, onDismiss = { editMonths = false }) { changed ->
                settings = settings.orEmpty() + (season to changed)
                editMonths = false
            }
            return
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (seasonOnly) "选择季节范围" else "事件时间") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!seasonOnly) Choice("填写方式 · ${kind.label}", Precision.entries, { it.label }) { kind = it; error = null }
            when (kind) {
                Precision.EXACT -> {
                    TimeField(date, { date = it }, "日期 · YYYY-MM-DD", "event-date")
                    TimeField(time, { time = it }, "时刻 · HH:mm（选填）", "event-time")
                    TimeField(zone, { zone = it }, "事件所在地时区", "event-zone")
                }
                Precision.MONTH -> TimeField(month, { month = it }, "月份 · YYYY-MM", "event-month")
                Precision.YEAR, Precision.SEASON -> TimeField(year, { year = it }, "四位年份", "event-year")
                Precision.RANGE, Precision.DESCRIPTION -> {
                    if (kind == Precision.DESCRIPTION) {
                        TimeField(description, { description = it }, "原来的说法", "event-description")
                        Text("知道范围时可填写以下两端，也可以只保留原话。")
                    }
                    TimeField(start, { start = it }, "开始 · 年、月或日期", "event-start")
                    TimeField(end, { end = it }, "结束 · 年、月或日期", "event-end")
                }
            }
            if (kind == Precision.SEASON) {
                Choice("选择季节 · ${season.label}", NamedSeason.entries, { it.label }) { season = it; error = null }
                selectedSeason?.let { Text("实际范围 ${it.dateRange.start} 至 ${it.dateRange.endInclusive}") }
                if (readFailed) TextButton(onClick = { reload++ }) { Text("季节月份读取失败，重试") }
                if (keepOriginal && settings?.get(season) != null && settings?.get(season) != months) {
                    TextButton(onClick = { useCurrentMonths = true }) { Text("采用当前季节设置") }
                }
                TextButton(enabled = settings != null, onClick = { editMonths = true }) { Text("调整季节月份") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!seasonOnly) TextButton(onClick = { save(null) }) { Text("暂不填写") }
        } },
        confirmButton = { TextButton(onClick = {
            try {
                val localZone = value?.zone ?: ZoneId.systemDefault()
                val result = when (kind) {
                    Precision.EXACT -> EventTime(LocalDate.parse(date.trim()),
                        time.trim().takeIf(String::isNotEmpty)?.let(LocalTime::parse), ZoneId.of(zone.trim()))
                    Precision.YEAR -> EventTime.Year(requireNotNull(seasonYear), localZone)
                    Precision.MONTH -> EventTime.Month(YearMonth.parse(month.trim()).also { require(it.year in 1000..9999) }, localZone)
                    Precision.SEASON -> requireNotNull(selectedSeason)
                    Precision.RANGE, Precision.DESCRIPTION -> {
                        val dates = if (kind == Precision.DESCRIPTION && start.isBlank() && end.isBlank()) null
                            else {
                                require(end.isNotBlank())
                                requireNotNull(EventDateFilter.Range.parse(start, end))
                            }
                        if (kind == Precision.RANGE) {
                            requireNotNull(dates)
                            EventTime.Range(dates.start, dates.end, localZone, dates.precision)
                        } else EventTime.Description(description.trim(), dates?.let { it.start..it.end }, localZone,
                            dates?.precision ?: RangePrecision())
                    }
                }
                save(result)
            } catch (_: RuntimeException) {
                error = when (kind) {
                    Precision.EXACT -> "请检查日期、时刻和时区是否有效。"
                    Precision.SEASON -> "请填写有效的四位年份，确认季节范围在 9999 年以内。"
                    else -> "请检查填写的时间，范围需完整且结束日期等于或晚于开始日期。"
                }
            }
        }, enabled = kind != Precision.SEASON || months != null) { Text(if (seasonOnly) "使用此范围" else "应用时间") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SeasonMonthsDialog(model: FolioViewModel, season: NamedSeason, current: SeasonMonths,
    onDismiss: () -> Unit, onSaved: (SeasonMonths) -> Unit) {
    var first by rememberSaveable { mutableStateOf(current.firstMonth.toString()) }
    var last by rememberSaveable { mutableStateOf(current.lastMonth.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("调整${season.label}的月份") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("新填写的季节采用此范围。结束月早于开始月时跨到次年。已有记录保留原范围。")
            TimeField(first, { first = it }, "开始月 · 1–12", "season-first")
            TimeField(last, { last = it }, "结束月 · 1–12", "season-last")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !saving, onClick = {
            val a = first.trim().toIntOrNull()
            val b = last.trim().toIntOrNull()
            if (a == null || b == null || a !in 1..12 || b !in 1..12) error = "月份须在 1 至 12 之间。"
            else {
                saving = true
                scope.launch {
                    try { val months = SeasonMonths(a, b); model.saveSeasonMonths(season, months); onSaved(months) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "季节月份保存失败，请重试。" }
                    finally { saving = false }
                }
            }
        }) { Text("保存季节设置") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}
