package com.kzhovn.todoapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyGraphTest {
    @Test
    fun `direct cycle is detected`() {
        // Task 2 already depends on task 1; adding "1 depends on 2" would close the loop.
        val edges = listOf(TaskDependency(taskId = 2, dependsOnTaskId = 1))
        assertTrue(wouldCreateDependencyCycle(candidateId = 2, editingTaskId = 1, edges = edges))
    }

    @Test
    fun `transitive cycle is detected`() {
        // 3 depends on 2, 2 depends on 1; adding "1 depends on 3" closes a 3-node loop.
        val edges = listOf(
            TaskDependency(taskId = 3, dependsOnTaskId = 2),
            TaskDependency(taskId = 2, dependsOnTaskId = 1)
        )
        assertTrue(wouldCreateDependencyCycle(candidateId = 3, editingTaskId = 1, edges = edges))
    }

    @Test
    fun `unrelated tasks do not create a cycle`() {
        val edges = listOf(TaskDependency(taskId = 2, dependsOnTaskId = 1))
        assertFalse(wouldCreateDependencyCycle(candidateId = 5, editingTaskId = 1, edges = edges))
    }
}
