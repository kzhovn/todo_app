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
        all.filter(searchMatcher(all, contexts, query, filters)).map { it.title }

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

    @Test
    fun `a context filter goes by inherited contexts too, and No context keeps tasks without any`() {
        val contexts = mapOf(1L to setOf(7L)) // the folder Work has @7
        assertEquals(listOf("Milk the report", "Walk the dog"), search("", SearchFilters(contextId = 7), contexts))
        assertEquals(listOf("Buy Milk"), search("", SearchFilters(contextId = SearchFilters.NO_CONTEXT), contexts))
    }

    @Test
    fun `typed @names come out of the search as a context, other @words stay`() {
        val home = TaskContext(id = 3, name = "Home", type = ContextType.LABEL)
        val phone = TaskContext(id = 4, name = "Phone call", type = ContextType.LABEL)
        assertEquals("milk @Sam" to 3L, parseSearch("@home  milk @Sam", listOf(home, phone)))
        assertEquals("" to 4L, parseSearch("@phonecall", listOf(home, phone)))
        assertEquals("milk" to null, parseSearch("milk", listOf(home)))
    }

    @Test
    fun `the pruned tree keeps matches and what's above them`() {
        val tree = pruneTree(buildOutlinerTree(all, hideCompleted = true), keep = setOf(11L))
        assertEquals(listOf("Work"), tree.map { it.task.title })
        assertEquals(listOf("Clients"), tree[0].children.map { it.task.title })
        assertEquals(listOf("Milk the report"), tree[0].children[0].children.map { it.task.title })
    }

    @Test
    fun `notes match too, and the matching line is shown only when the title didn't match`() {
        val bank = Task(id = 20, title = "Call the bank", notes = "Ask about the fee\nRef 4417-22")
        assert(searchMatcher(listOf(bank), emptyMap(), "4417", SearchFilters())(bank))
        assertEquals("Ref 4417-22", notesMatch(bank, "4417"))
        assertEquals(null, notesMatch(bank, "bank"))
        val long = Task(id = 21, title = "Trip", notes = "word ".repeat(30) + "passport " + "more ".repeat(30))
        val cut = notesMatch(long, "passport")!!
        assert(cut.startsWith("…") && cut.endsWith("…") && "passport" in cut && cut.length < 120) { cut }
    }
}
