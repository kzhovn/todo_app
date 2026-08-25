package com.kzhovn.todoapp.recurrence

import com.kzhovn.todoapp.data.RecurrenceType
import org.junit.Assert.assertEquals
import org.junit.Test

class RecurrencePresetTest {
    @Test
    fun `NONE maps to null recurrence fields`() {
        assertEquals(null to null, RecurrenceSelection(RecurrencePreset.NONE).toTaskFields())
    }

    @Test
    fun `DAILY maps to FREQ=DAILY`() {
        assertEquals(RecurrenceType.RRULE to "FREQ=DAILY", RecurrenceSelection(RecurrencePreset.DAILY).toTaskFields())
    }

    @Test
    fun `WEEKLY maps to FREQ=WEEKLY`() {
        assertEquals(RecurrenceType.RRULE to "FREQ=WEEKLY", RecurrenceSelection(RecurrencePreset.WEEKLY).toTaskFields())
    }

    @Test
    fun `MONTHLY maps to FREQ=MONTHLY`() {
        assertEquals(RecurrenceType.RRULE to "FREQ=MONTHLY", RecurrenceSelection(RecurrencePreset.MONTHLY).toTaskFields())
    }

    @Test
    fun `EVERY_N_DAYS maps to an interval RRULE`() {
        assertEquals(
            RecurrenceType.RRULE to "FREQ=DAILY;INTERVAL=4",
            RecurrenceSelection(RecurrencePreset.EVERY_N_DAYS, n = 4).toTaskFields()
        )
    }

    @Test
    fun `AFTER_COMPLETION_N_DAYS maps to a bare day count`() {
        assertEquals(
            RecurrenceType.AFTER_COMPLETION to "3",
            RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 3).toTaskFields()
        )
    }

    @Test
    fun `a task with no recurrence round-trips to NONE`() {
        assertEquals(RecurrenceSelection(RecurrencePreset.NONE), recurrenceSelectionFromTask(null, null))
    }

    @Test
    fun `every-N-days round-trips through toTaskFields and back`() {
        val (type, rule) = RecurrenceSelection(RecurrencePreset.EVERY_N_DAYS, n = 6).toTaskFields()
        assertEquals(RecurrenceSelection(RecurrencePreset.EVERY_N_DAYS, n = 6), recurrenceSelectionFromTask(type, rule))
    }

    @Test
    fun `after-completion round-trips through toTaskFields and back`() {
        val (type, rule) = RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 10).toTaskFields()
        assertEquals(RecurrenceSelection(RecurrencePreset.AFTER_COMPLETION_N_DAYS, n = 10), recurrenceSelectionFromTask(type, rule))
    }

    @Test
    fun `an unrecognized custom RRULE falls back to NONE rather than crashing`() {
        assertEquals(
            RecurrenceSelection(RecurrencePreset.NONE),
            recurrenceSelectionFromTask(RecurrenceType.RRULE, "FREQ=YEARLY;BYMONTH=12")
        )
    }
}
