package com.baconaa.folio.records

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class RecordObjectFlowTest : PhotoStoreFixture() {
    private suspend fun draft(text: String) = store.saveDraft(null, TextEdit(text, 0))!!

    @Test fun privateObjectsPublishByIdentityAndSharedEditsKeepReferencesAndOldDrafts() = runBlocking {
        val first = draft("送礼")
        val person = store.createDraftObject(first, ObjectKind.PERSON, ObjectInfo(" 小李 ", " 同事 ", listOf("李姐", " 李姐 ", "", "小李")))
        val thing = store.createDraftObject(first, ObjectKind.THING, ObjectInfo("杯子", "白色"))
        val second = draft("聚餐")
        val other = store.createDraftObject(second, ObjectKind.PERSON, ObjectInfo("小李", "大学同学"))
        assertTrue(store.listObjects().isEmpty())
        assertTrue(store.search("李姐").isEmpty())
        assertTrue(runCatching { store.saveDraftObjects(second, listOf(person)) }.isFailure)
        assertTrue(runCatching { store.saveDraftObjects(first, listOf(person, person)) }.isFailure)
        assertTrue(runCatching { store.updateObject(person, ObjectInfo("共享越界")) }.isFailure)
        reopenDatabase()
        assertEquals(ObjectInfo("小李", "同事", listOf("李姐")), store.openDraftObjects(first)!!.first().info)
        val record = store.completeMixedDraft(first)
        val otherRecord = store.completeMixedDraft(second)
        assertEquals(listOf(record), store.search("李姐").map { it.id })
        assertEquals(setOf(record, otherRecord), store.queryRecords("小李").map { it.id }.toSet())
        val edit = store.editRecord(record)
        val times = store.openRecordContent(record)!!.times
        store.updateObject(person, ObjectInfo("李阿姨", "原同事", listOf("李姐", "a%b_c", "新别名")))
        assertEquals(times, store.openRecordContent(record)!!.times)
        assertEquals("李阿姨", store.openDraftObjects(edit)!!.first().info.name)
        store.completeMixedDraft(edit)
        assertEquals(times, store.openRecordContent(record)!!.times)
        assertEquals(listOf(record), store.queryRecords("新别名").map { it.id })
        assertEquals(listOf(record), store.search("a%b_c").map { it.id })
        assertTrue(store.queryRecords("aZZbxc").isEmpty())
        assertEquals(listOf(person, thing), store.openRecordContent(record)!!.objects.map { it.id })
        assertNotEquals(person, other)
        val change = store.editRecord(record)
        store.saveDraftObjects(change, listOf(other))
        assertEquals(listOf(record), store.queryRecords(peopleIds = setOf(person)).map { it.id })
        store.completeMixedDraft(change)
        assertTrue(store.queryRecords(peopleIds = setOf(person)).isEmpty())
        assertEquals(setOf(record, otherRecord), store.queryRecords(peopleIds = setOf(other)).map { it.id }.toSet())
        assertTrue(store.listObjects().any { it.id == person })
        assertTrue(store.listObjects().any { it.id == thing })
    }

    @Test fun peopleAndThingsAreSeparateCategoriesWithOrWithinEachAndTrashIsExcluded() = runBlocking {
        val bothDraft = draft("外婆送杯子")
        val grandma = store.createDraftObject(bothDraft, ObjectKind.PERSON, ObjectInfo("外婆", aliases = listOf("阿婆")))
        val cup = store.createDraftObject(bothDraft, ObjectKind.THING, ObjectInfo("搪瓷杯"))
        val tag = store.createDraftTag(bothDraft, "礼物")
        val place = store.createDraftPlace(bothDraft, PlaceInfo("老家"))
        val both = store.completeMixedDraft(bothDraft)
        store.setStarred(both, true)
        val peopleDraft = draft("陪外婆吃饭")
        store.saveDraftObjects(peopleDraft, listOf(grandma))
        val people = store.completeMixedDraft(peopleDraft)
        val thingDraft = draft("给杯子配盖")
        store.saveDraftObjects(thingDraft, listOf(cup))
        val thing = store.completeMixedDraft(thingDraft)
        val otherDraft = draft("妈妈送杯子")
        val mom = store.createDraftObject(otherDraft, ObjectKind.PERSON, ObjectInfo("妈妈"))
        store.saveDraftObjects(otherDraft, listOf(mom, cup))
        val other = store.completeMixedDraft(otherDraft)
        assertEquals(listOf(both), store.queryRecords(peopleIds = setOf(grandma), thingIds = setOf(cup)).map { it.id })
        assertEquals(setOf(both, other), store.queryRecords(peopleIds = setOf(grandma, mom), thingIds = setOf(cup)).map { it.id }.toSet())
        assertEquals(listOf(both), store.queryRecords("阿婆", setOf(tag), true, place, setOf(grandma), setOf(cup)).map { it.id })
        assertTrue(store.queryRecords(peopleIds = setOf(cup)).isEmpty())
        val edit = store.editRecord(both)
        store.saveDraftObjects(edit, listOf(mom))
        store.moveToTrash(both)
        assertEquals(listOf(people), store.queryRecords("阿婆").map { it.id })
        assertEquals(setOf(thing, other), store.queryRecords(thingIds = setOf(cup)).map { it.id }.toSet())
        assertNull(store.openDraftObjects(edit))
        assertEquals(listOf(grandma, cup), store.openTrashRecord(both)!!.content.objects.map { it.id })
        reopenDatabase()
        store.restoreRecord(both)
        assertEquals(listOf(mom), store.openDraftObjects(edit)!!.map { it.id })
        assertEquals(listOf(both), store.queryRecords(peopleIds = setOf(grandma), thingIds = setOf(cup)).map { it.id })
        store.moveToTrash(both)
        store.deletePermanently(both)
        assertNull(store.openDraftObjects(edit))
        assertEquals(3, store.listObjects().size)
    }

    @Test fun failedCommitRollsBackPromotionAndDiscardCleansPrivateObjectsAndAliases() = runBlocking {
        val first = draft("原记录")
        val old = store.createDraftObject(first, ObjectKind.THING, ObjectInfo("白杯"))
        val record = store.completeMixedDraft(first)
        val edit = store.editRecord(record)
        val unused = store.createDraftObject(edit, ObjectKind.THING, ObjectInfo("未选", aliases = listOf("多余")))
        val next = store.createDraftObject(edit, ObjectKind.THING, ObjectInfo("蓝杯", aliases = listOf("蓝色杯")))
        store.saveDraftObjects(edit, listOf(next))
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_object BEFORE DELETE ON text_drafts BEGIN SELECT RAISE(ABORT, 'object_failure'); END")
        try {
            assertTrue(runCatching { store.completeMixedDraft(edit) }.isFailure)
            assertEquals(listOf(old), store.openRecordContent(record)!!.objects.map { it.id })
            assertFalse(store.listObjects().any { it.id == next })
            assertEquals(next, store.openDraftObjects(edit)!!.single().id)
        } finally { sql.execSQL("DROP TRIGGER reject_object") }
        assertEquals(record, store.completeMixedDraft(edit))
        assertEquals(record, store.completeMixedDraft(edit))
        assertNull(database.objectDao().objectById(unused.value))
        assertEquals(listOf(record), store.search("蓝色杯").map { it.id })
        val discard = draft("丢弃")
        val private = store.createDraftObject(discard, ObjectKind.PERSON, ObjectInfo("临时", aliases = listOf("应删除")))
        store.discardDraft(discard)
        assertNull(database.objectDao().objectById(private.value))
        sql.query("SELECT count(*) FROM object_aliases WHERE objectId = ?", arrayOf(private.value)).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }
}
