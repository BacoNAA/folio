package com.baconaa.folio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.baconaa.folio.records.*
import kotlin.math.abs
import kotlin.math.cos

internal data class PlaceStar(val x: Float, val y: Float, val places: List<Place>)

/** 点间距离按经纬度近似投影，跨日界线时取包住所有地点的最短经度区间。 */
internal fun placeStars(places: List<Place>, width: Float, height: Float): List<PlaceStar> {
    val located = places.filter { it.info.coordinates != null }.sortedBy { it.id.value }
    if (located.isEmpty()) return emptyList()
    val coords = located.map { requireNotNull(it.info.coordinates) }
    val longitudes = coords.map { it.longitude }.distinct().sorted()
    val gap = longitudes.indices.maxBy { index ->
        if (index == longitudes.lastIndex) longitudes.first() + 360 - longitudes.last() else longitudes[index + 1] - longitudes[index]
    }
    val west = longitudes[(gap + 1) % longitudes.size]
    fun longitude(point: PlaceCoordinates) = (point.longitude - west + 360) % 360
    val span = coords.maxOf(::longitude)
    val south = coords.minOf { it.latitude }
    val north = coords.maxOf { it.latitude }
    val latitudeScale = cos(Math.toRadians((north + south) / 2)).coerceAtLeast(0.00001)
    val availableWidth = (width - 48).coerceAtLeast(0f)
    val availableHeight = (height - 48).coerceAtLeast(0f)
    val scale = minOf(if (span == 0.0) Double.MAX_VALUE else availableWidth / (span * latitudeScale),
        if (north == south) Double.MAX_VALUE else availableHeight / (north - south))
    val stars = mutableListOf<PlaceStar>()
    located.forEachIndexed { index, place ->
        val coord = coords[index]
        val x = (availableWidth / 2 + (longitude(coord) - span / 2) * latitudeScale * scale).toFloat()
        val y = (availableHeight / 2 + ((north + south) / 2 - coord.latitude) * scale).toFloat()
        val group = stars.indexOfFirst { abs(it.x - x) < 48 && abs(it.y - y) < 48 }
        if (group < 0) stars.add(PlaceStar(x, y, listOf(place)))
        else stars[group] = stars[group].copy(places = stars[group].places + place)
    }
    return stars
}

@Composable
internal fun PlaceStarChart(model: FolioViewModel) {
    val state = model.state
    val places = remember(state.places) { state.places.map { it.place } }
    if (state.placesLoading || state.placesError != null) return
    if (places.none { it.info.coordinates != null }) {
        Text("当前地点没有已保存的坐标。可在列表中打开地点小册。")
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val plotHeight = 204.dp
        val stars = remember(places, maxWidth) { placeStars(places, maxWidth.value, plotHeight.value) }
        val selected = stars.firstOrNull { star -> star.places.any { it.id == state.selectedStar } }
        LazyColumn(Modifier.fillMaxWidth().testTag("star-chart")) {
            item {
                Text("上北下南 · 点选星点查看地点", style = MaterialTheme.typography.labelMedium)
                Box(Modifier.fillMaxWidth().height(plotHeight).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    stars.forEach { star ->
                        val first = star.places.first()
                        val label = if (star.places.size == 1) first.info.name else "${first.info.name}等 ${star.places.size} 处地点"
                        IconButton(onClick = { model.selectPlaceStar(first.id) },
                            modifier = Modifier.offset(star.x.dp, star.y.dp).size(48.dp)
                                .testTag("star-${first.id.value}").semantics { contentDescription = label }) {
                            Box(Modifier.size(if (star.places.size > 1) 32.dp else 18.dp)
                                .background(if (star == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, CircleShape),
                                contentAlignment = Alignment.Center) {
                                if (star.places.size > 1) Text(star.places.size.toString(), color = MaterialTheme.colorScheme.onSecondary)
                            }
                        }
                    }
                }
            }
            items(selected?.places.orEmpty(), key = { it.id.value }) { place ->
                TextButton(onClick = { model.selectPlaceStar(place.id); model.openPlace(place) },
                    modifier = Modifier.fillMaxWidth().testTag("star-book-${place.id.value}")) {
                    Column(Modifier.fillMaxWidth()) { PlaceDescription(place) }
                }
            }
        }
    }
}
