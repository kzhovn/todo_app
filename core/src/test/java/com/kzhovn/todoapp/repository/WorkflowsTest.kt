package com.kzhovn.todoapp.repository

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.data.timeLeft
import com.kzhovn.todoapp.data.waitingToCheck
import com.kzhovn.todoapp.quickadd.planQuickAdd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowsTest {
    private val min = 60_000L
    private val now = 1_790_000_000_000L
    private val laundry = Task(id = 1, title = "Laundry", type = TaskType.PROJECT, sequential = true)
    private val start = Task(id = 2, title = "Start wash", parentId = 1)
    private val washing = Task(id = 3, title = "Washing", type = TaskType.WAITING, durationMinutes = 50, parentId = 1)
    private val switch = Task(id = 4, title = "Switch to dryer", parentId = 1)
    private val takeOut = Task(id = 5, title = "Take it out", parentId = 1)
    private val all = listOf(laundry, start, washing, switch, takeOut)

    private fun complete(tasks: List<Task>, id: Long, at: Long = now) = planComplete(tasks, emptyMap(), id, at).applyTo(tasks)
    private fun active(tasks: List<Task>, at: Long) = computeActiveTasks(tasks, emptyMap(), emptyList(), emptyList(), emptyList(), at).map { it.title }
    private fun List<Task>.byId(id: Long) = single { it.id == id }

    @Test fun tickingAStepStartsTheWaitAfterItAndStarsTheStepAfterThat() {
        val after = complete(all, 2)
        assertEquals(now + 50 * min, after.byId(3).dueDate)
        assertEquals(now + 50 * min, after.byId(3).remindAt)
        assertTrue(after.byId(4).isStarred)
        assertFalse(after.byId(5).isStarred)
    }

    @Test fun theNextStepIsHeldBackUntilTheWaitEndsThenUpOnTheMinute() {
        val after = complete(all, 2)
        assertEquals(emptyList<String>(), active(after, now + 49 * min))
        assertEquals(listOf("Switch to dryer"), active(after, now + 50 * min))
    }

    @Test fun everyStepThatComesUpWhileItRunsIsStarred() {
        val after = complete(complete(complete(all, 2), 3, now + 50 * min), 4, now + 60 * min)
        assertTrue(after.byId(5).isStarred)
    }

    @Test fun tickingTheLastStepFinishesTheWorkflow() {
        val done = complete(complete(complete(complete(all, 2), 3, now + 50 * min), 4, now + 60 * min), 5, now + 90 * min)
        assertTrue(done.byId(1).isComplete)
    }

    @Test fun aWaitResolvedLateTimesTheNextWaitFromWhenItEnded() {
        val drying = Task(id = 6, title = "Drying", type = TaskType.WAITING, durationMinutes = 90, parentId = 1)
        val tasks = listOf(laundry, washing.copy(dueDate = now), drying)
        assertEquals(now + 90 * min, complete(tasks, 3, now + 7 * min).byId(6).dueDate)
    }

    @Test fun tickingTheNextStepBeforeAnEndedWaitIsResolvedStillStartsTheWaitAfterIt() {
        val drying = Task(id = 6, title = "Drying", type = TaskType.WAITING, durationMinutes = 90, parentId = 1)
        val tasks = complete(listOf(laundry, start, washing, switch, drying, takeOut.copy(id = 7)), 2)
        val after = complete(tasks, 4, now + 55 * min)
        assertEquals(now + 145 * min, after.byId(6).dueDate)
        assertTrue(after.byId(7).isStarred)
    }

    @Test fun aStepThatDoesntHoldUpTheNextComesUpWithItAndTheWaitAfterRunsWithoutIt() {
        val takeWet = Task(id = 2, title = "Take wet out", parentId = 1)
        val hang = Task(id = 3, title = "Hang the rest", parentId = 1, alongsideNext = true)
        val dryer = Task(id = 4, title = "Start dryer", parentId = 1)
        val drying = Task(id = 5, title = "Drying", type = TaskType.WAITING, durationMinutes = 90, parentId = 1)
        val takeOutDry = Task(id = 6, title = "Take out of dryer", parentId = 1)
        val tasks = complete(listOf(laundry, takeWet, hang, dryer, drying, takeOutDry), 2)
        assertEquals(listOf("Hang the rest", "Start dryer"), active(tasks, now))
        assertTrue(tasks.byId(3).isStarred && tasks.byId(4).isStarred)
        val dryerStarted = complete(tasks, 4, now + min)
        assertEquals(now + 91 * min, dryerStarted.byId(5).dueDate)
        assertTrue(dryerStarted.byId(6).isStarred)
        assertEquals(listOf("Hang the rest"), active(dryerStarted, now + 2 * min))
        assertEquals(listOf("Hang the rest", "Take out of dryer"), active(dryerStarted, now + 91 * min))
        // Ticking it later starts nothing new, and reopening it takes nothing back.
        val hung = complete(dryerStarted, 3, now + 5 * min)
        assertEquals(now + 91 * min, planUncomplete(hung, 3).applyTo(hung).byId(5).dueDate)
    }

    @Test fun aWaitThatDoesntHoldUpTheNextStartsAndTheNextStepComesUpAtOnce() {
        val rising = washing.copy(title = "Rising", alongsideNext = true)
        val tasks = complete(listOf(laundry, start, rising, switch), 2)
        assertEquals(now + 50 * min, tasks.byId(3).dueDate)
        assertEquals(listOf("Switch to dryer"), active(tasks, now))
    }

    @Test fun inOrderProjectsWithoutATimedWaitStarNothing() {
        val plain = listOf(laundry, start, switch)
        assertFalse(complete(plain, 2).byId(4).isStarred)
    }

    @Test fun reopeningAStepTakesBackTheWaitItStarted() {
        val ticked = complete(all, 2)
        val reopened = planUncomplete(ticked, 2).applyTo(ticked)
        assertNull(reopened.byId(3).dueDate)
        assertNull(reopened.byId(3).remindAt)
    }

    @Test fun aKitchenTimerStartsWhenItsMadeButAStepWaitsForTheStepBefore() {
        val pasta = Task(title = "Pasta", type = TaskType.WAITING, durationMinutes = 10)
        assertEquals(now + 10 * min, planCreate(emptyList(), pasta, now = now).creates.single().first.dueDate)
        assertNull(planCreate(listOf(laundry, start), washing.copy(id = 0), now = now).creates.single().first.dueDate)
    }

    @Test fun aTimedWaitIsNeverCheckedIn() {
        assertEquals(emptyList<Task>(), waitingToCheck(listOf(washing.copy(id = (now - 10 * 24 * 60 * min) shl 11)), now))
    }

    @Test fun theRunningLineHasEachWaitAndTheStepAfterIt() {
        val running = runningWaits(complete(all, 2), now + 18 * min).single()
        assertEquals("Washing", running.wait.title)
        assertEquals("Switch to dryer", running.next?.title)
        assertEquals("32m left", timeLeft(running.wait.dueDate!!, now + 18 * min))
        assertEquals(emptyList<RunningWait>(), runningWaits(complete(all, 2), now + 50 * min))
    }

    @Test fun aRepeatStartsTheNextRunAfresh() {
        val weekly = laundry.copy(recurrenceType = com.kzhovn.todoapp.data.RecurrenceType.AFTER_COMPLETION, recurrenceRule = "7", startDate = now)
        val ran = listOf(weekly, start.copy(isComplete = true), washing.copy(isComplete = true, dueDate = now), switch.copy(isComplete = true, isStarred = true))
        val copies = planComplete(ran, emptyMap(), 1, now + 2 * 60 * min).creates.map { it.first }.filter { it.parentId != null }
        assertTrue(copies.none { it.isStarred })
        assertNull(copies.single { it.title == "Washing" }.dueDate)
    }

    @Test fun usingAWorkflowTemplateStarsItsFirstStep() {
        val template = listOf(laundry, start, washing, switch).map { it.copy(isTemplate = true) }
        val (change, id) = planUseTemplate(template, emptyMap(), emptyList(), 1, now)!!
        val made = change.applyTo(template).filter { it.parentId == id }
        assertTrue(made.single { it.title == "Start wash" }.isStarred)
        assertFalse(made.single { it.title == "Switch to dryer" }.isStarred)
    }

    @Test fun quickAddArrowsMakeAnInOrderProjectWithWaits() {
        fun plan(text: String) = planQuickAdd(text, emptyList(), emptyList(), now, 4)
        assertEquals(listOf(false, true, false), plan("Laundry > take wet out &> hang the rest > start dryer").steps.map { it.second })
        val add = plan("Laundry > start wash > 50m > switch to dryer > wait drying ~1.5h > hang it")
        assertEquals(TaskType.PROJECT, add.task!!.type)
        assertTrue(add.task!!.sequential)
        val (change, id) = planQuickAddWrite(emptyList(), add, null, now, ::plan)
        val steps = change.applyTo(emptyList()).filter { it.parentId == id }.sortedWith(com.kzhovn.todoapp.data.TaskOrder)
        assertEquals(listOf("start wash", "Wait", "switch to dryer", "drying", "hang it"), steps.map { it.title })
        assertEquals(listOf(null, 50, null, 90, null), steps.map { it.durationMinutes })
        assertTrue(steps.none { it.dueDate != null })
        assertEquals("Compare A > B", plan("Compare A > B").task!!.title)
    }
}
