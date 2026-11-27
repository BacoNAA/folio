package com.baconaa.folio.ui

import com.baconaa.folio.records.*
import org.junit.Assert.*
import org.junit.Test

class PlaceStarsTest {
    private fun place(id: String, lat: Double, lon: Double) =
        Place(PlaceId(id), PlaceInfo("咖啡馆", address = id, coordinates = PlaceCoordinates(lat, lon)))

    @Test fun closestPointsKeepDistinctIdentitiesAndCoordinatesCanChange() {
        val a = place("西门", 30.0, 120.0)
        val b = place("西侧楼上", 30.00001, 120.00001)
        val c = place("东门", 30.0, 121.0)
        val unmapped = Place(PlaceId("未知"), PlaceInfo("咖啡馆"))
        val stars = placeStars(listOf(a,b,c,unmapped), 400f, 200f)
        val west = stars.single { a in it.places }
        assertEquals(setOf(a,b), west.places.toSet())
        assertTrue(stars.single { c in it.places }.x > west.x)
        assertEquals(2, stars.size)
        val moved = place("西门", 32.0, 120.0)
        val updated = placeStars(listOf(moved,b,c), 400f, 200f)
        assertTrue(updated.single { moved in it.places }.y < updated.single { b in it.places }.y)
    }

    @Test fun singlePointIsCenteredAndDateLineUsesShorterArc() {
        val a = place("甲", 0.0, 179.0)
        val b = place("乙", 0.0, -179.0)
        val only = placeStars(listOf(a), 200f, 200f).single()
        assertEquals(76f, only.x, 0.01f)
        assertEquals(76f, only.y, 0.01f)
        val stars = placeStars(listOf(a,b), 200f, 200f)
        assertTrue(stars.single { a in it.places }.x < stars.single { b in it.places }.x)
    }
}
