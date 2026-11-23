package com.baconaa.folio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baconaa.folio.records.*
import com.baconaa.folio.ui.ExternalTarget
import com.baconaa.folio.ui.FolioViewModel
import com.baconaa.folio.ui.Page
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordGroupStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = LocalTextStore.get(instrumentation.targetContext)
    private lateinit var model: FolioViewModel

    private fun settled(action: () -> Unit) {
        instrumentation.runOnMainSync(action)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var idle = false
            instrumentation.runOnMainSync { idle = !model.state.busy && !model.state.loading }
            if (idle) return
            SystemClock.sleep(20)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("记录组操作未结束")
    }

    @Test fun unknownCreateResultReusesSavedIdAndFailedDetailReadRetriesOnlyTheRead() {
        val ids = mutableListOf<RecordGroupId>()
        val groups = object : RecordGroups by store {
            var failRead = true
            override suspend fun createGroup(id: RecordGroupId, name: String, members: List<RecordId>): GroupUpdate {
                ids.add(id)
                val result = store.createGroup(id, name, members)
                if (ids.size == 1) throw IOException("模拟写入后丢失响应")
                return result
            }
            override suspend fun openGroup(id: RecordGroupId): RecordGroupContent? {
                if (failRead) { failRead = false; throw IOException("模拟首次读取失败") }
                return store.openGroup(id)
            }
        }
        val name = "收录重试${System.nanoTime()}"
        val record = runBlocking { store.completeMixedDraft(store.saveDraft(null, TextEdit("保修单", 0))!!) }
        try {
            settled { model = FolioViewModel(store, store, store, store, recordGroups = groups); model.home(Page.Groups) }
            settled { model.pickGroupMembers() }
            settled { model.toggleGroupMember(record); model.home(Page.GroupDestination) }
            settled { model.nameNewGroup(name); model.saveGroupSelection(null) }
            assertNotNull(model.state.error)
            val route = requireNotNull(model.state.groupNavigation())
            settled { model = FolioViewModel(store, store, store, store, recordGroups = groups); model.readGroupNavigation(route) }
            assertEquals(name, model.state.groupSelection!!.name)
            assertEquals(listOf(record), model.state.groupSelection!!.members)
            settled { model.saveGroupSelection(null) }
            assertEquals(Page.GroupDetail, model.state.page)
            assertNotNull(model.state.groupReadError)
            assertEquals(2, ids.size)
            assertEquals(ids.first(), ids.last())
            settled { model.refresh() }
            assertNull(model.state.groupReadError)
            assertEquals(listOf(record), model.state.groupContent!!.members.map { it.id })
            assertEquals(2, ids.size)
            assertEquals(1, runBlocking { store.listGroups().count { it.name == name } })
        } finally {
            runBlocking {
                ids.distinct().forEach { store.deleteGroup(it) }
                store.moveToTrash(record)
                store.deletePermanently(record)
            }
        }
    }

    @Test fun cancelledFilePickerRestoresTheMemberDraftAndItsGroupInANewViewModel() {
        val record = runBlocking { store.completeMixedDraft(store.saveDraft(null, TextEdit("原凭证", 0))!!) }
        val group = RecordGroupId("凭证组${System.nanoTime()}")
        lateinit var target: ExternalTarget
        try {
            runBlocking { store.createGroup(group, "凭证", listOf(record)) }
            settled { model = FolioViewModel(store, store, store, store); model.openGroup(group) }
            settled { model.openRecord(record) }
            settled { model.editRecord() }
            val draft = requireNotNull(model.state.editor?.draftId)
            settled { model.prepareExternal(null) { target = it } }
            settled {
                model = FolioViewModel(store, store, store, store)
                model.receiveDocument(null, ImportType.PDF, target)
            }
            assertEquals(draft, model.state.editor?.draftId)
            settled { model.back() }
            assertEquals(record, model.state.detailId)
            settled { model.back() }
            assertEquals(Page.GroupDetail, model.state.page)
            assertEquals(group, model.state.groupContent?.group?.id)
            assertEquals(listOf(record), model.state.groupContent?.members?.map { it.id })
        } finally {
            runBlocking { store.deleteGroup(group); store.moveToTrash(record); store.deletePermanently(record) }
        }
    }
}
