package com.kzhovn.todoapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    private val work = Task(id = 1, title = "Work", type = TaskType.FOLDER)
    private val clients = Task(id = 2, title = "Clients", type = TaskType.FOLDER, parentId = 1)
    private val all = listOf(
        work, clients,
        Task(id = 10, title = "Buy Milk"),
        Task(id = 11, title = "Milk the report", parentId = 2),
        Task(id = 12, title = "Walk the dog", parentId = 1),
        Task(id = 13, title = "50% off milk", isComplete = true),
        Task(id = 14, title = "Milk Projects", type = TaskType.FOLDER)
    )
    private fun search(query: String, filters: SearchFilters = SearchFilters(), contexts: Map<Long, Set<Long>> = emptyMap()) =
        searchTasks(all, contexts, query, filters).map { it.title }

    @Test
    fun `matches any case, ignores surrounding spaces, skips folders and completed tasks`() {
        assertEquals(listOf("Buy Milk", "Milk the report"), search("  milk "))
    }

    @Test
    fun `percent and underscore match themselves`() {
        assertEquals(listOf("50% off milk"), search("50%", SearchFilters(includeCompleted = true)))
        assertEquals(emptyList<String>(), search("_"))
    }

    @Test
    fun `a folder filter includes its subfolders`() {
        assertEquals(listOf("Milk the report", "Walk the dog"), search("", SearchFilters(folderId = 1)))
        assertEquals(listOf("Milk the report"), search("", SearchFilters(folderId = 2)))
    }

    @Test
    fun `a context filter keeps tasks with that context`() {
        assertEquals(listOf("Walk the dog"), search("", SearchFilters(contextId = 7), mapOf(12L to setOf(7L))))
    }
}
