package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeEditTest {
    @Test fun editedFieldsWinAndEverythingElseKeepsWhatChangedMeanwhile() {
        val base = Task(id = 1, title = "Call mom")
        val edited = base.copy(title = "Call mom back", checkInDays = 7)
        val current = base.copy(isComplete = true, completedAt = 5L, timerEndsAt = 9L)
        assertEquals(current.copy(title = "Call mom back", checkInDays = 7), mergeEdit(base, edited, current))
    }

    @Test fun aRepeatsTypeAndRuleGoTogether() {
        val base = Task(id = 1, title = "Bins", recurrenceType = RecurrenceType.AFTER_COMPLETION, recurrenceRule = "7")
        val edited = base.copy(recurrenceType = RecurrenceType.RRULE, recurrenceRule = "FREQ=WEEKLY")
        val current = base.copy(recurrenceRule = "14") // changed elsewhere meanwhile
        assertEquals(RecurrenceType.RRULE to "FREQ=WEEKLY", mergeEdit(base, edited, current).let { it.recurrenceType to it.recurrenceRule })
    }

    @Test fun aFolderCarriesNoReminders() {
        val folder = Task(id = 1, title = "Work", type = TaskType.FOLDER, startDate = 1L, remindAtStart = true, remindAt = 2L)
        assertEquals(false to null, folder.typeRule().let { it.remindAtStart to it.remindAt })
    }
}
