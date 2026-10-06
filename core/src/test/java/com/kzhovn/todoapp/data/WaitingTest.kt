package com.kzhovn.todoapp.data

import com.kzhovn.todoapp.quickadd.QuickAddParser
import com.kzhovn.todoapp.repository.computeActiveTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingTest {
    private val day = 24L * 60 * 60 * 1000
    private val made = 1_790_000_000_000L // ids embed creation time (id shr 11)
    private val roommate = Task(id = made shl 11, title = "Roommate decides", type = TaskType.WAITING)

    @Test fun firstCheckInComesThreeDaysAfterItWasMade() {
        assertEquals(emptyList<Task>(), waitingToCheck(listOf(roommate), made + 2 * day))
        assertEquals(listOf(roommate), waitingToCheck(listOf(roommate), made + 3 * day))
    }

    @Test fun stillWaitingHidesItUntilTheNextCheckIn() {
        val later = made + 4 * day
        val hidden = stillWaiting(roommate.copy(checkInDays = 7), later)
        assertEquals(emptyList<Task>(), waitingToCheck(listOf(hidden), later + 6 * day))
        assertEquals(1, waitingToCheck(listOf(hidden), later + 7 * day).size)
    }

    @Test fun aDatedOneNeverComesUpAndResolvesOnItsDay() {
        val inspection = roommate.copy(dueDate = made + 10 * day)
        assertEquals(emptyList<Task>(), waitingToCheck(listOf(inspection), made + 5 * day))
        assertFalse(inspection.resolvesBy(made + 9 * day))
        assertTrue(inspection.resolvesBy(made + 10 * day))
    }

    @Test fun itBlocksItsDependentsButIsNeverActiveItself() {
        val spare = Task(id = 2, title = "Clear the spare room")
        val active = computeActiveTasks(listOf(roommate, spare), emptyMap(), emptyList(), emptyList(), listOf(TaskDependency(2, roommate.id)), made)
        assertEquals(emptyList<Task>(), active)
    }

    @Test fun quickAddWaitMakesOne() {
        assertEquals(TaskType.WAITING to "roommate decides", QuickAddParser.parse("wait roommate decides").let { it.type to it.title })
        assertEquals(TaskType.TASK, QuickAddParser.parse("wait").type)
        assertEquals(TaskType.TASK, QuickAddParser.parse("don't wait up").type)
    }
}
