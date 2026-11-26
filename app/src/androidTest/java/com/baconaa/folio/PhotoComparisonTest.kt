package com.baconaa.folio

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.baconaa.folio.media.LocalPhotoOriginals
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.FolioViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class PhotoComparisonTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity.applicationContext
    private val store get() = LocalTextStore.get(context)

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(matcher.description, 15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }

    @Test fun selectedItemsRetainCaptionsAndSurvivingSideWhenOtherSourceIsTrashed() {
        val name = "阳台${System.nanoTime()}"
        val image = runBlocking {
            LocalPhotoOriginals.get(context).importPhoto(Uri.parse("android.resource://${context.packageName}/${R.drawable.home_art}"))
        }
        val first = runBlocking {
            val draft = store.appendPhotos(null, listOf(image, image))
            val items = store.openMixedDraft(draft)!!.items
            store.editPhotoCaption(draft, items[0].id, "客厅")
            store.editPhotoCaption(draft, items[1].id, "搬家时的阳台")
            store.saveDraftTitle(draft, "入住$name")
            store.completeMixedDraft(draft) to items[1].id
        }
        val second = runBlocking {
            val draft = store.appendPhotos(null, listOf(image))
            val item = store.openMixedDraft(draft)!!.items.single()
            store.editPhotoCaption(draft, item.id, "开花的阳台")
            store.saveDraftTitle(draft, "今年$name")
            store.completeMixedDraft(draft) to item.id
        }
        try {
            compose.runOnIdle { ViewModelProvider(compose.activity)[FolioViewModel::class.java].openRecord(first.first) }
            node(hasTestTag("compare-start-${first.second.value}")).performScrollTo().performClick()
            node(hasText("搬家时的阳台")).assertExists()
            compose.onNodeWithText("客厅").assertDoesNotExist()
            node(hasTestTag("compare-right")).performClick()
            node(hasTestTag("photo-source-${second.first.value}")).performScrollTo().performClick()
            node(hasTestTag("compare-item-${second.second.value}")).performClick()
            node(hasText("开花的阳台")).assertExists()
            node(hasTestTag("compare-source-right")).performScrollTo().performClick()
            node(hasTestTag("compare-start-${second.second.value}")).assertExists()
            runBlocking { store.moveToTrash(second.first) }
            node(hasText("返回")).performClick()
            node(hasText("来源照片已移除或在回收站中")).assertExists()
            node(hasText("搬家时的阳台")).assertExists()
            node(hasTestTag("compare-source-left")).performScrollTo().performClick()
            node(hasTestTag("compare-start-${first.second.value}")).assertExists()
        } finally {
            runBlocking {
                listOf(first.first, second.first).forEach { store.moveToTrash(it); store.deletePermanently(it) }
            }
        }
    }
}
