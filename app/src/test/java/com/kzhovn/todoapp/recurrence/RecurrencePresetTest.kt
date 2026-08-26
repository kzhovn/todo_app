package com.kzhovn.todoapp.recurrence

import com.kzhovn.todoapp.data.RecurrenceType
import org.junit.Assert.assertEquals
import org.junit.Test

class RecurrencePresetTest {
    @Test
    fun `none maps to null fields`() {
        val (type, rule) = RecurrenceSelection(RecurrencePreset.NONE).toTaskFields()
        assertEquals(null, type)
        assertEquals(null, rule)
    }

    @Test
    fun `every 1 day maps to bare FREQ=DAILY`() {
        val (type, rule) = RecurrenceSelection(RecurrencePreset.CALENDAR, n = 1, unit = RecurrenceUnit.DAY).toTaskFields()
        assertEquals(RecurrenceType.RRULE, type)
        assertEquals("FREQ=DAILY", rule)
    }

    @Test
    fun `every 3 days includes INTERVAL`() {
        val (_, rule) = RecurrenceSelection(RecurrencePreset.CALENDAR, n = 3, unit = RecurrenceUnit.DAY).toTaskFields()
        assertEquals("FREQ=DAILY;INTERVAL=3", rule)
    }

    @Test
    fun `every 1 week maps to bare FREQ=WEEKLY`() {
        val (_, rule) = RecurrenceSelection(RecurrencePreset.CALENDAR, n = 1, unit = RecurrenceUnit.WEEK).toTaskFields()
        assertEquals("FREQ=WEEKLY", rule)
    }

    @Test
    fun `every 1 month maps to bare FREQ=MONTHLY`() {
        val (_, rule) = RecurrenceSelection(RecurrencePreset.CALENDAR, n = 1, unit = RecurrenceUnit.MONTH).toTaskFields()
        assertEquals("FREQ=MONTHLY", rule)
    }

    @Test
    fun `weekly with weekdays adds BYDAY in Su-Mo-Tu order`() {
        // Monday (bit 1) + Wednesday (bit 3) + Friday (bit 5)
        val mask = (1 shl 1) or (1 shl 3) or (1 shl 5)
        val (_, rule) = RecurrenceSelection(RecurrencePreset.CALENDAR, n = 1, unit = RecurrenceUnit.WEEK, weekdaysMask = mask).toTaskFields()
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", rule)
    }

    @Test
    fun `weekly with interval and weekdays combines both`() {
        val mask = (1 shl 2) // Tuesday
        val (_, rule) = RecurrenceSelection(RecurrencePreset.CALENDAR, n = 2, unit = RecurrenceUnit.WEEK, weekdaysMask = mask).toTaskFields()
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU", rule)
    }

    @Test
    fun `after completion maps to AFTER_COMPLETION with n as the rule string`() {
        val (type, rule) = RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 5).toTaskFields()
        assertEquals(RecurrenceType.AFTER_COMPLETION, type)
        assertEquals("5", rule)
    }

    @Test
    fun `parsing null fields returns NONE`() {
        assertEquals(RecurrenceSelection(RecurrencePreset.NONE), recurrenceSelectionFromTask(null, null))
    }

    @Test
    fun `parsing AFTER_COMPLETION round-trips n`() {
        val selection = recurrenceSelectionFromTask(RecurrenceType.AFTER_COMPLETION, "7")
        assertEquals(RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 7), selection)
    }

    @Test
    fun `parsing bare FREQ=DAILY round-trips to every 1 day`() {
        val selection = recurrenceSelectionFromTask(RecurrenceType.RRULE, "FREQ=DAILY")
        assertEquals(RecurrenceSelection(RecurrencePreset.CALENDAR, n = 1, unit = RecurrenceUnit.DAY), selection)
    }

    @Test
    fun `parsing FREQ=DAILY with INTERVAL round-trips n`() {
        val selection = recurrenceSelectionFromTask(RecurrenceType.RRULE, "FREQ=DAILY;INTERVAL=4")
        assertEquals(RecurrenceSelection(RecurrencePreset.CALENDAR, n = 4, unit = RecurrenceUnit.DAY), selection)
    }

    @Test
    fun `parsing FREQ=WEEKLY with BYDAY round-trips the weekday mask`() {
        val selection = recurrenceSelectionFromTask(RecurrenceType.RRULE, "FREQ=WEEKLY;BYDAY=MO,WE,FR")
        val expectedMask = (1 shl 1) or (1 shl 3) or (1 shl 5)
        assertEquals(RecurrenceSelection(RecurrencePreset.CALENDAR, n = 1, unit = RecurrenceUnit.WEEK, weekdaysMask = expectedMask), selection)
    }

    @Test
    fun `parsing an unrecognized FREQ falls back to NONE`() {
        val selection = recurrenceSelectionFromTask(RecurrenceType.RRULE, "FREQ=YEARLY")
        assertEquals(RecurrenceSelection(RecurrencePreset.NONE), selection)
    }
}
