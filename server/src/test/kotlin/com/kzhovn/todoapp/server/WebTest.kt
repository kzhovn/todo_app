package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.sync.SyncRequest
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.sync.taskFields
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebTest {
    private val store = Store(File.createTempFile("web", ".db").apply { deleteOnExit() }.path)
    private val service = TaskService(store)
    private fun web(enabled: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { api(store, "token", service, webEnabled = enabled) }
        block()
    }

    @Test
    fun `pages exist only when enabled, and every response carries the security headers`() {
        web {
            val doing = client.get("/doing")
            assertEquals(HttpStatusCode.OK, doing.status)
            assertTrue(doing.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
            assertEquals(HttpStatusCode.OK, client.get("/static/htmx/htmx.min.js").status)
        }
        web(enabled = false) {
            assertEquals(HttpStatusCode.NotFound, client.get("/doing").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/sync").status)
        }
    }

    @Test
    fun `quick add lands in Personal, and completing shows an undo toast`() = web {
        val personal = service.create(Task(type = TaskType.FOLDER, title = "Personal"))

        client.submitForm("/quickadd", parameters { append("text", "buy milk due today"); append("mode", "DOING") })
        val milk = service.tasks().single { it.title == "buy milk" }
        assertEquals(personal.id, milk.parentId)

        val response = client.post("/tasks/${milk.id}/complete?mode=DOING").bodyAsText()
        assertTrue(service.get(milk.id)!!.isComplete)
        assertTrue(response.contains("Undo"))

        client.post("/tasks/${milk.id}/uncomplete?mode=DOING")
        assertFalse(service.get(milk.id)!!.isComplete)
    }

    @Test
    fun `projects render without a checkbox, subtasks with a marker`() = web {
        val project = service.create(Task(type = TaskType.PROJECT, title = "Wool coat"))
        service.create(Task(title = "Cut fabric", parentId = project.id, isStarred = true))
        val all = client.get("/all").bodyAsText()
        val title = all.indexOf("Wool coat")
        val projectRow = all.substring(all.lastIndexOf("class=\"row", title), title)
        assertTrue(projectRow.contains("class=\"project\""))
        assertFalse(projectRow.contains("class=\"check"))
        val doing = client.get("/doing").bodyAsText()
        assertTrue(doing.contains("href=\"/tasks/${project.id}?mode=DOING\" class=\"parent\""))
        assertTrue(doing.contains(">Wool coat: </a>"))
    }

    @Test
    fun `the All tree hides completed tasks as the phone does`() = web {
        val done = service.create(Task(title = "Old milestone"))
        service.create(Task(title = "Next step", parentId = done.id))
        val list = service.create(Task(type = TaskType.CHECKLIST, title = "Packing"))
        service.complete(done.id)
        service.completeChecklist(list.id, moveUncheckedToNewList = false)
        val all = client.get("/list/all").bodyAsText()
        assertFalse(all.contains(">Old milestone<"))
        assertTrue(all.contains(">Next step<")) // its open subtask moves up
        assertTrue(all.contains(">Packing<")) // a completed checklist stays, struck through
        assertTrue(all.substring(all.lastIndexOf("class=\"row", all.indexOf(">Packing<"))).startsWith("class=\"row done"))
    }

    @Test
    fun `editing applies only what the form changed, keeping edits synced in meanwhile`() = web {
        val task = service.create(Task(title = "Water plants"))
        val base = taskFields(task, emptySet(), emptySet()).toString()
        service.setStarred(task.id, true) // from the phone, after the editor loaded

        client.submitForm("/tasks/${task.id}?mode=ALL", parameters {
            append("base", base); append("title", "Water the plants"); append("type", "TASK")
        })
        val saved = service.get(task.id)!!
        assertEquals("Water the plants", saved.title)
        assertTrue(saved.isStarred)
    }

    @Test
    fun `changing an inherited date asks about subtasks that set their own`() = web {
        val parent = service.create(Task(title = "Trip"))
        val child = service.create(Task(title = "Pack", parentId = parent.id, dueDate = 1_000_000))
        val form = parameters {
            append("base", taskFields(parent, emptySet(), emptySet()).toString())
            append("title", "Trip"); append("type", "TASK"); append("dueDate", "2026-10-01")
        }
        assertTrue(client.submitForm("/tasks/${parent.id}", form).bodyAsText().contains("Update subtasks"))
        assertEquals(null, service.get(parent.id)!!.dueDate)

        client.submitForm("/tasks/${parent.id}", parameters { appendAll(form); append("inherit", "update") })
        assertTrue(service.get(parent.id)!!.dueDate != null)
        assertEquals(null, service.get(child.id)!!.dueDate)
    }

    @Test
    fun `a project needs a first step`() = web {
        val task = service.create(Task(title = "Wool coat"))
        val form = parameters {
            append("base", taskFields(task, emptySet(), emptySet()).toString())
            append("title", "Wool coat"); append("type", "PROJECT")
        }
        assertTrue(client.submitForm("/tasks/${task.id}", form).bodyAsText().contains("needs a first step"))
        client.submitForm("/tasks/${task.id}", parameters { appendAll(form); append("firstStep", "Buy wool") })
        assertEquals(TaskType.PROJECT, service.get(task.id)!!.type)
        assertEquals("Buy wool", service.tasks().single { it.parentId == task.id }.title)
    }

    @Test
    fun `delete offers undo`() = web {
        val task = service.create(Task(title = "Oops"))
        val noRedirects = createClient { followRedirects = false }
        val location = noRedirects.post("/tasks/${task.id}/delete?mode=ACTIVE").headers["Location"]!!
        assertEquals(null, service.get(task.id))
        assertTrue(client.get(location).bodyAsText().contains("Deleted “Oops”"))
        client.post("/tasks/${task.id}/restore?mode=ACTIVE")
        assertEquals("Oops", service.get(task.id)?.title)
    }

    @Test
    fun `posts from other sites are refused`() = web {
        val task = service.create(Task(title = "Keep me"))
        assertEquals(HttpStatusCode.Forbidden, client.post("/tasks/${task.id}/delete") { header(HttpHeaders.Origin, "https://evil.example") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/tasks/${task.id}/delete") { header(HttpHeaders.Origin, "null") }.status)
        assertEquals("Keep me", service.get(task.id)?.title)
        client.post("/tasks/${task.id}/star") { header(HttpHeaders.Origin, "http://localhost"); header(HttpHeaders.Host, "localhost") }
        assertTrue(service.get(task.id)!!.isStarred)
    }

    @Test
    fun `snoozing to tomorrow lasts until the day rollover`() = web {
        val task = service.create(Task(title = "Later"))
        client.post("/tasks/${task.id}/snooze?until=tomorrow&mode=ACTIVE")
        val start = service.get(task.id)!!.startDate!!
        assertEquals(com.kzhovn.todoapp.data.nextRollover(service.now(), service.rolloverHour()), start)
    }

    @Test
    fun `outliner moves tasks like the phone's outliner`() = web {
        val folder = service.create(Task(type = TaskType.FOLDER, title = "Work"))
        val a = service.create(Task(title = "A", parentId = folder.id))
        val b = service.create(Task(title = "B", parentId = folder.id))
        fun order() = service.tasks().filter { it.parentId == folder.id }.sortedWith(com.kzhovn.todoapp.data.TaskOrder).map { it.title }

        client.post("/outline/${b.id}/up")
        assertEquals(listOf("B", "A"), order())
        client.post("/outline/${a.id}/indent") // under B, the sibling above
        assertEquals(b.id, service.get(a.id)!!.parentId)
        client.post("/outline/${a.id}/outdent") // back, right after B
        assertEquals(listOf("B", "A"), order())
        client.submitForm("/outline/${a.id}/move", parameters { append("target", b.id.toString()); append("zone", "before") })
        assertEquals(listOf("A", "B"), order())
        client.submitForm("/outline/${b.id}/move", parameters { append("target", a.id.toString()); append("zone", "into") })
        assertEquals(a.id, service.get(b.id)!!.parentId)
        // A folder can't be moved into its own subtree.
        client.submitForm("/outline/${folder.id}/move", parameters { append("target", a.id.toString()); append("zone", "into") })
        assertEquals(null, service.get(folder.id)!!.parentId)
    }

    @Test
    fun `enter adds a sibling right after the task, and folding is remembered per browser`() = web {
        val folder = service.create(Task(type = TaskType.FOLDER, title = "Work"))
        service.create(Task(title = "B", parentId = folder.id))
        val a = service.create(Task(title = "A", parentId = folder.id)) // new tasks go on top
        val response = client.submitForm("/outline/${a.id}/sibling", parameters { append("text", "A2") })
        val created = service.tasks().single { it.title == "A2" }
        assertEquals(created.id.toString(), response.headers["X-Focus"])
        assertEquals(listOf("A", "A2", "B"), service.tasks().filter { it.parentId == folder.id }.sortedWith(com.kzhovn.todoapp.data.TaskOrder).map { it.title })

        val browser = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }
        assertFalse(browser.get("/list/all?toggle=${folder.id}").bodyAsText().contains(">A2<"))
        assertFalse(browser.get("/list/all").bodyAsText().contains(">A2<")) // still folded
        assertTrue(client.get("/list/all").bodyAsText().contains(">A2<")) // another browser isn't
        assertTrue(browser.get("/list/all?toggle=${folder.id}").bodyAsText().contains(">A2<"))
    }

    @Test
    fun `search filters like the phone's`() = web {
        val work = service.create(Task(type = TaskType.FOLDER, title = "Work"))
        service.create(Task(title = "Fix bug", parentId = work.id, isStarred = true))
        service.create(Task(title = "Old bug", isComplete = true))
        service.create(Task(title = "Buy milk"))
        suspend fun titles(query: String) = Regex("mode=ALL\"[^>]*>([^<]+)</a>").findAll(client.get("/search/results?$query").bodyAsText()).map { it.groupValues[1] }.toList()

        assertEquals(listOf("Fix bug"), titles("q=BUG"))
        assertEquals(listOf("Fix bug", "Old bug"), titles("q=bug&completed=on"))
        assertEquals(listOf("Fix bug"), titles("folder=${work.id}"))
        assertEquals(listOf("Fix bug"), titles("starred=on"))
    }

    @Test
    fun `contexts are created, edited and deleted from every task`() = web {
        val noRedirects = createClient { followRedirects = false }
        assertTrue(client.submitForm("/contexts", parameters { append("name", "Office"); append("type", "TIME") }).bodyAsText().contains("at least one window"))

        noRedirects.submitForm("/contexts", parameters {
            append("name", "Office"); append("type", "TIME")
            append("start", "09:00"); append("end", "17:30"); append("days0", "1"); append("days0", "5")
            append("start", ""); append("end", "")
        })
        val office = service.contexts().single()
        val window = service.timeWindows(office.id).single()
        assertEquals(listOf(540, 1050, 0b100010), listOf(window.windowStartMinute, window.windowEndMinute, window.daysMask))

        noRedirects.submitForm("/contexts", parameters { append("id", office.id.toString()); append("name", "Home"); append("type", "PLACE"); append("ssid", "HomeNet") })
        assertEquals(listOf("Home" to "HomeNet"), service.contexts().map { it.name to it.wifiSsid })

        val task = service.create(Task(title = "Water plants"))
        service.edit(task, setOf(office.id), emptySet())
        client.post("/contexts/${office.id}/delete")
        assertEquals(emptyList<Any>(), service.contexts())
        assertEquals(emptySet<Long>(), service.contextIdsByTask()[task.id])
    }

    @Test
    fun `review counts completions by window, steps back, and quick add off a list confirms with a toast`() = web {
        val day = 24L * 60 * 60 * 1000
        service.create(Task(title = "Done today", isComplete = true, completedAt = service.now(), dueDate = service.now() - 3 * day))
        service.create(Task(title = "Still open"))
        val review = client.get("/review").bodyAsText()
        assertTrue(review.contains("Due dates"))
        assertTrue(review.contains("3d late"))
        assertTrue(review.contains("Done today"))
        assertTrue(review.substringAfter("Waiting now").contains("Still open"))

        val earlier = java.time.LocalDate.now().minusDays(20)
        val week = client.get("/review?zoom=week&end=$earlier").bodyAsText()
        assertTrue(week.contains("Back to today"))
        assertFalse(week.contains(">Done today<"))
        assertTrue(client.get("/review?zoom=year").bodyAsText().contains("By month"))

        assertTrue(client.submitForm("/quickadd", parameters { append("text", "call mom") }).bodyAsText().contains("Added “call mom”"))
    }

    @Test
    fun `completing a task with open subtasks asks first, then completes or moves them out`() = web {
        val folder = service.create(Task(type = TaskType.FOLDER, title = "Personal"))
        val trip = service.create(Task(title = "Plan trip", parentId = folder.id))
        val hotel = service.create(Task(title = "Book hotel", parentId = trip.id))
        assertTrue(client.post("/tasks/${trip.id}/complete?mode=ALL").bodyAsText().contains("1 active subtask"))
        assertFalse(service.get(trip.id)!!.isComplete)

        client.post("/tasks/${trip.id}/complete?mode=ALL&subtasks=promote")
        assertTrue(service.get(trip.id)!!.isComplete)
        assertEquals(null, service.get(hotel.id)!!.parentId) // moved out to the top level, like the phone
        assertFalse(service.get(hotel.id)!!.isComplete)

        val pack = service.create(Task(title = "Pack"))
        service.create(Task(title = "Socks", parentId = pack.id)).let { socks ->
            client.post("/tasks/${pack.id}/complete?mode=ALL&subtasks=complete")
            assertTrue(service.get(socks.id)!!.isComplete)
        }
    }

    @Test
    fun `a project with all subtasks done is asked about until completed, extended or put off`() = web {
        val project = service.create(Task(type = TaskType.PROJECT, title = "Wool coat"))
        service.create(Task(title = "Cut fabric", parentId = project.id, isComplete = true))
        assertTrue(client.get("/list/active").bodyAsText().contains("“Wool coat”: all subtasks done"))

        client.submitForm("/tasks/${project.id}/next?mode=ACTIVE", parameters { append("text", "Sew lining") })
        assertFalse(client.get("/list/active").bodyAsText().contains("all subtasks done"))

        service.complete(service.tasks().single { it.title == "Sew lining" }.id)
        val browser = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }
        assertFalse(browser.get("/list/active?later=${project.id}").bodyAsText().contains("all subtasks done"))
        assertFalse(browser.get("/list/active").bodyAsText().contains("all subtasks done")) // for this session
    }

    @Test
    fun `new tasks, projects and folders are made in the full editor`() = web {
        val personal = service.create(Task(type = TaskType.FOLDER, title = "Personal"))
        assertTrue(Regex("""name="parent" value="${personal.id}" checked""").containsMatchIn(client.get("/tasks/new?type=TASK").bodyAsText()))

        fun blank(type: TaskType) = taskFields(Task(title = "", type = type), emptySet(), emptySet()).toString()
        val noRedirects = createClient { followRedirects = false }
        noRedirects.submitForm("/tasks/new?mode=ALL", parameters { append("base", blank(TaskType.FOLDER)); append("title", "Hobby"); append("type", "FOLDER") })
        assertEquals(TaskType.FOLDER, service.tasks().single { it.title == "Hobby" }.type)

        val project = parameters { append("base", blank(TaskType.PROJECT)); append("title", "Wool coat"); append("type", "PROJECT"); append("starred", "on") }
        assertTrue(client.submitForm("/tasks/new", project).bodyAsText().contains("needs a first step"))
        assertEquals(null, service.tasks().firstOrNull { it.title == "Wool coat" })
        noRedirects.submitForm("/tasks/new", parameters { appendAll(project); append("newSubtasks", "Buy wool\n\nCut fabric") })
        val coat = service.tasks().single { it.title == "Wool coat" }
        assertTrue(coat.isStarred)
        assertEquals(listOf("Buy wool", "Cut fabric"), service.tasks().filter { it.parentId == coat.id }.map { it.title }.sorted())
    }

    @Test
    fun `bulk edit changes only what it's told, and skips folders`() = web {
        val work = service.create(Task(type = TaskType.FOLDER, title = "Work"))
        val office = service.saveContext(com.kzhovn.todoapp.data.TaskContext(name = "Office", type = com.kzhovn.todoapp.data.ContextType.PLACE, wifiSsid = "Corp"), emptyList())
        val blocker = service.create(Task(title = "Get keys"))
        val a = service.create(Task(title = "A", isStarred = true, dueDate = 1_000_000))
        val b = service.create(Task(title = "B"))
        val folder = service.create(Task(type = TaskType.FOLDER, title = "Not me"))
        client.submitForm("/bulk?mode=DOING", parameters {
            listOf(a, b, folder).forEach { append("id", it.id.toString()) }
            append("star", "unstar"); append("maybe", "keep"); append("dueClear", "on")
            append("folder", work.id.toString()); append("addCtx", office.id.toString()); append("dependsOn", blocker.id.toString())
        })
        listOf(a, b).map { service.get(it.id)!! }.forEach {
            assertFalse(it.isStarred)
            assertEquals(null, it.dueDate)
            assertEquals(work.id, it.parentId)
            assertEquals(setOf(office.id), service.contextIdsByTask()[it.id])
            assertEquals(setOf(blocker.id), service.dependsOn(it.id))
        }
        assertEquals(null, service.get(folder.id)!!.parentId)
    }

    @Test
    fun `search results complete and reopen tasks, and quick add in Doing stars`() = web {
        val done = service.create(Task(title = "Old bug", isComplete = true, completedAt = 1))
        client.submitForm("/search/toggle/${done.id}", parameters { append("q", "bug"); append("completed", "on") })
        assertFalse(service.get(done.id)!!.isComplete)

        client.submitForm("/quickadd", parameters { append("text", "call mom"); append("mode", "DOING") })
        client.submitForm("/quickadd", parameters { append("text", "call dad"); append("mode", "ACTIVE") })
        assertTrue(service.tasks().single { it.title == "call mom" }.isStarred)
        assertFalse(service.tasks().single { it.title == "call dad" }.isStarred)
    }

    @Test
    fun `a new dependent task lands in the task's folder and waits for it`() = web {
        val folder = service.create(Task(type = TaskType.FOLDER, title = "Home"))
        val paint = service.create(Task(title = "Buy paint", parentId = folder.id))
        client.submitForm("/tasks/${paint.id}/dependent", parameters { append("text", "Paint the wall") })
        val wall = service.tasks().single { it.title == "Paint the wall" }
        assertEquals(folder.id, wall.parentId)
        assertEquals(setOf(paint.id), service.dependsOn(wall.id))
    }

    @Test
    fun `a reminder set on the web is saved on the task, for the phone to schedule`() = web {
        val task = service.create(Task(title = "Dentist"))
        createClient { followRedirects = false }.submitForm("/tasks/${task.id}", parameters {
            append("base", taskFields(task, emptySet(), emptySet()).toString())
            append("title", "Dentist"); append("type", "TASK"); append("dueDate", "2026-10-01"); append("dueTime", "15:00"); append("reminder", "30")
        })
        assertEquals(30, service.get(task.id)!!.reminderOffsetMinutes)
    }

    @Test
    fun `depends on can create the task to wait for, in the same folder`() = web {
        val folder = service.create(Task(type = TaskType.FOLDER, title = "Home"))
        val wall = service.create(Task(title = "Paint the wall", parentId = folder.id))
        createClient { followRedirects = false }.submitForm("/tasks/${wall.id}", parameters {
            append("base", taskFields(wall, emptySet(), emptySet()).toString())
            append("title", "Paint the wall"); append("type", "TASK"); append("parent", folder.id.toString()); append("newDep", "Buy paint")
        })
        val paint = service.tasks().single { it.title == "Buy paint" }
        assertEquals(folder.id, paint.parentId)
        assertEquals(setOf(paint.id), service.dependsOn(wall.id))
    }

    @Test
    fun `a new task goes first in its folder, below the subfolders, but last under a task`() = web {
        val personal = service.create(Task(type = TaskType.FOLDER, title = "Personal"))
        service.create(Task(title = "task 1", parentId = personal.id))
        service.create(Task(type = TaskType.FOLDER, title = "Folder 1", parentId = personal.id))
        service.create(Task(type = TaskType.FOLDER, title = "Folder 2", parentId = personal.id))
        service.create(Task(title = "task 2", parentId = personal.id))
        val project = service.create(Task(title = "task 3", parentId = personal.id))
        service.create(Task(title = "step 1", parentId = project.id))
        service.create(Task(title = "step 2", parentId = project.id))
        fun titles(parent: Long) = service.tasks().filter { it.parentId == parent }.sortedWith(com.kzhovn.todoapp.data.TaskOrder).map { it.title }
        assertEquals(listOf("Folder 1", "Folder 2", "task 3", "task 2", "task 1"), titles(personal.id))
        assertEquals(listOf("step 1", "step 2"), titles(project.id))
    }

    @Test
    fun `a new task can be saved with a new dependent, made in its folder`() = web {
        val work = service.create(Task(type = TaskType.FOLDER, title = "Work"))
        client.submitForm("/tasks/new", parameters {
            append("title", "Write draft"); append("type", "TASK"); append("parent", work.id.toString()); append("newDependent", "Send draft")
        })
        val draft = service.tasks().single { it.title == "Write draft" }
        val send = service.tasks().single { it.title == "Send draft" }
        assertEquals(work.id, send.parentId)
        assertEquals(setOf(draft.id), service.dependsOn(send.id))
    }

    @Test
    fun `a checklist's items are added, kept out of Active, and moved on to a new list when completed`() = web {
        val list = service.create(Task(title = "Groceries", type = TaskType.CHECKLIST))
        client.submitForm("/tasks/${list.id}/items", parameters { append("text", "milk, eggs, bread") })
        val items = service.tasks().filter { it.parentId == list.id }
        assertEquals(setOf("milk", "eggs", "bread"), items.map { it.title }.toSet())
        assertEquals(listOf("Groceries"), service.active().map { it.title })
        service.complete(items.first { it.title == "milk" }.id)
        client.post("/tasks/${list.id}/complete-list?move=1")
        val fresh = service.tasks().single { it.title == "Groceries" && !it.isComplete }
        assertEquals(setOf("eggs", "bread"), service.tasks().filter { it.parentId == fresh.id }.map { it.title }.toSet())
        assertTrue(service.get(list.id)!!.isComplete)
    }

    @Test
    fun `notes save from the editor, show as a preview with links, mark rows and turn up in search`() = web {
        val task = service.create(Task(title = "Call the bank", isStarred = true))
        client.submitForm("/tasks/${task.id}/autosave", parameters {
            append("base", taskFields(task, emptySet(), emptySet()).toString())
            append("title", "Call the bank"); append("type", "TASK"); append("starred", "on")
            append("notes", "Ask about the fee\r\nRef 4417, see https://bank.example/help.  ")
        })
        assertEquals("Ask about the fee\nRef 4417, see https://bank.example/help.", service.get(task.id)!!.notes)
        val editor = client.get("/tasks/${task.id}?mode=DOING").bodyAsText()
        assertTrue(editor.contains("<a href=\"https://bank.example/help\" target=\"_blank\" rel=\"noopener\">"))
        assertTrue(client.get("/doing").bodyAsText().contains("has-notes"))
        assertTrue(client.get("/search/results?q=4417").bodyAsText().contains("notes-line\">Ref 4417"))
        // Cleared: gone, not an empty string.
        client.submitForm("/tasks/${task.id}/autosave", parameters {
            append("base", taskFields(service.get(task.id)!!, emptySet(), emptySet()).toString())
            append("title", "Call the bank"); append("type", "TASK"); append("starred", "on"); append("notes", "")
        })
        assertNull(service.get(task.id)!!.notes)
    }

    @Test
    fun `auto-save writes a change in place, but leaves one needing a question to Save`() = web {
        val task = service.create(Task(title = "Call the dentist"))
        client.submitForm("/tasks/${task.id}/autosave", parameters {
            append("base", taskFields(task, emptySet(), emptySet()).toString())
            append("title", "Call the dentist today"); append("type", "TASK")
        }).bodyAsText().let { assertTrue(it.contains("Saved")) }
        assertEquals("Call the dentist today", service.get(task.id)!!.title)

        val project = service.create(Task(title = "Paint", type = TaskType.PROJECT))
        client.submitForm("/tasks/${project.id}/autosave", parameters {
            append("base", taskFields(project, emptySet(), emptySet()).toString())
            append("title", "Paint the fence"); append("type", "PROJECT")
        }).bodyAsText().let { assertTrue(it.contains("press Save")) }
        assertEquals("Paint", service.get(project.id)!!.title) // a project needs its first step, asked on Save

        // A subtask with its own due date doesn't hold up a change to the start date (Save wouldn't ask).
        val parent = service.create(Task(title = "Trip"))
        service.create(Task(title = "Book", parentId = parent.id, dueDate = 1_800_000_000_000L))
        client.submitForm("/tasks/${parent.id}/autosave", parameters {
            append("base", taskFields(parent, emptySet(), emptySet()).toString())
            append("title", "Trip"); append("type", "TASK"); append("startDate", "2026-10-01")
        }).bodyAsText().let { assertTrue(it.contains("Saved")) }
    }

    @Test
    fun `active is sectioned by top-level folder, foldable per browser, no-folder only when needed`() = web {
        val work = service.create(Task(type = TaskType.FOLDER, title = "Work"))
        service.create(Task(title = "Fix bug", parentId = work.id))
        val active = client.get("/list/active").bodyAsText()
        assertTrue(active.contains("<b>Work</b> · 1"))
        assertFalse(active.contains("No folder"))

        service.create(Task(title = "Loose end"))
        val browser = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }
        assertTrue(browser.get("/list/active").bodyAsText().contains("No folder"))
        val folded = browser.get("/list/active?fold=${work.id}").bodyAsText()
        assertFalse(folded.contains("Fix bug"))
        assertTrue(folded.contains("Loose end"))
        assertFalse(browser.get("/list/active").bodyAsText().contains("Fix bug")) // remembered
    }

    @Test
    fun `timed tasks come from quick add or the editor and get a play button`() = web {
        client.submitForm("/quickadd", parameters { append("text", "1 hour of ticket work"); append("mode", "DOING") })
        val ticket = service.tasks().single { it.title == "ticket work" }
        assertEquals(60, ticket.durationMinutes)
        val doing = client.get("/list/doing").bodyAsText()
        assertTrue(doing.contains("data-minutes=\"60\""))
        assertTrue(doing.contains(">1h<"))

        createClient { followRedirects = false }.submitForm("/tasks/${ticket.id}", parameters {
            append("base", taskFields(ticket, emptySet(), emptySet()).toString())
            append("title", "ticket work"); append("type", "TASK"); append("duration", "25")
        })
        assertEquals(25, service.get(ticket.id)!!.durationMinutes)
    }

    @Test
    fun `related tasks add and unlink prerequisites and dependents at once`() = web {
        val folder = service.create(Task(type = TaskType.FOLDER, title = "Work"))
        val report = service.create(Task(title = "Write report", parentId = folder.id))
        val vpn = service.create(Task(title = "Get VPN access"))
        client.submitForm("/tasks/${report.id}/prerequisite", parameters { append("prerequisite", vpn.id.toString()) })
        client.submitForm("/tasks/${report.id}/prerequisite", parameters { append("text", "Collect numbers") })
        val numbers = service.tasks().single { it.title == "Collect numbers" }
        assertEquals(folder.id, numbers.parentId)
        assertEquals(setOf(vpn.id, numbers.id), service.dependsOn(report.id))

        client.post("/tasks/${report.id}/prerequisite/${vpn.id}/remove")
        assertEquals(setOf(numbers.id), service.dependsOn(report.id))
        client.post("/tasks/${numbers.id}/dependent/${report.id}/remove")
        assertEquals(emptySet<Long>(), service.dependsOn(report.id))

        // Saving the editor leaves prerequisites alone: they're no longer part of its form.
        service.addDependency(report.id, vpn.id)
        createClient { followRedirects = false }.submitForm("/tasks/${report.id}", parameters {
            append("base", taskFields(service.get(report.id)!!, emptySet(), emptySet()).toString())
            append("title", "Write the report"); append("type", "TASK"); append("parent", folder.id.toString())
        })
        assertEquals(setOf(vpn.id), service.dependsOn(report.id))
    }

    @Test
    fun `a subtask that's also a dependent is one row`() = web {
        val trip = service.create(Task(title = "Plan trip"))
        val book = service.create(Task(title = "Book hotel", parentId = trip.id))
        service.addDependency(book.id, trip.id)
        val related = client.get("/tasks/${trip.id}").bodyAsText().substringAfter("id=\"related\"")
        assertEquals(1, Regex(">Book hotel<").findAll(related).count())
        assertTrue(related.contains(">${Labels.DEPENDENT}<")) // the subtask says so after its title
        client.post("/tasks/${trip.id}/dependent/${book.id}/remove")
        assertEquals(trip.id, service.get(book.id)!!.parentId)
        assertEquals(emptySet<Long>(), service.dependsOn(book.id))
    }

    @Test
    fun `the pin is shared, the newest pin wins, and the editor toggles it`() = web {
        val a = service.create(Task(title = "Call mom"))
        val b = service.create(Task(title = "Buy milk"))
        assertTrue(client.post("/tasks/${a.id}/pin?mode=DOING").bodyAsText().contains("Pinned “Call mom”"))
        assertEquals(a.id, service.pinned()?.id)
        service.pin(b.id)
        assertEquals(b.id, service.pinned()?.id)
        assertEquals(null, service.get(a.id)!!.pinnedAt) // the older pin is cleared

        assertTrue(client.get("/tasks/${b.id}").bodyAsText().contains("pin-toggle on"))
        assertTrue(client.post("/tasks/${b.id}/pin-toggle").bodyAsText().contains("class=\"pin-toggle\""))
        assertEquals(null, service.pinned())

        // A save from the editor keeps a pin made meanwhile (it isn't a form field).
        val base = taskFields(a, emptySet(), emptySet()).toString()
        service.pin(a.id)
        client.submitForm("/tasks/${a.id}?mode=DOING", parameters { append("base", base); append("title", "Call mom!"); append("type", "TASK") })
        assertEquals(a.id, service.pinned()?.id)
        service.complete(a.id)
        assertEquals(null, service.pinned()) // done: no longer pinned
    }

    @Test
    fun `a folder's view shows just its tasks, quick add lands there, and a task opens beside its list`() = web {
        val work = service.create(Task(type = TaskType.FOLDER, title = "Work"))
        val home = service.create(Task(type = TaskType.FOLDER, title = "Home"))
        val report = service.create(Task(title = "Send report", parentId = work.id))
        service.create(Task(title = "Dishes", parentId = home.id))

        val view = client.get("/all?folder=${work.id}").bodyAsText()
        assertTrue(view.contains(">Send report<"))
        assertFalse(view.contains(">Dishes<"))
        assertTrue(view.contains("href=\"/all?folder=${work.id}\" class=\"current\"")) // the folder is highlighted in the sidebar

        client.post("/quickadd?folder=${work.id}") { header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString()); setBody("text=Book+room&mode=ALL") }
        assertEquals(work.id, service.tasks().single { it.title == "Book room" }.parentId)

        // The editor comes with its list, the task's row marked, and closes back to the folder's view.
        val editor = client.get("/tasks/${report.id}?mode=ALL&folder=${work.id}").bodyAsText()
        assertTrue(editor.contains("id=\"list\""))
        assertTrue(Regex("class=\"row[^\"]*current").containsMatchIn(editor))
        assertTrue(editor.contains("class=\"detail-close\"") && editor.contains("href=\"/all?folder=${work.id}\" class=\"detail-close\""))
    }

    @Test
    fun `a new task can be pinned from the editor, pinned when it's saved`() = web {
        assertTrue(client.get("/tasks/new?type=TASK").bodyAsText().contains("name=\"pin\""))
        client.submitForm("/tasks/new", parameters { append("title", "Write draft"); append("type", "TASK"); append("pin", "on") })
        assertEquals("Write draft", service.pinned()?.title)
        client.submitForm("/tasks/new", parameters { append("title", "Unpinned"); append("type", "TASK") })
        assertEquals("Write draft", service.pinned()?.title)
    }

    @Test
    fun `a focus session takes over every page until it's left, which unpins`() = web {
        val call = service.create(Task(title = "Call mom", isStarred = true))
        client.post("/focus/start?task=${call.id}") { header("HX-Request", "true") }
        assertEquals(call.id, service.focusSession()?.id)
        assertEquals(call.id, service.pinned()?.id) // focusing pins

        val page = client.get("/doing").bodyAsText()
        assertTrue(page.contains("id=\"focus\""))
        assertTrue(page.contains(">Call mom<"))
        // A list refreshing itself on another open page reloads it, into focus.
        assertEquals("true", client.get("/list/doing").headers["HX-Refresh"])

        // Done: the session asks what's next.
        assertTrue(client.post("/focus/done").bodyAsText().contains("Focus on the next task, or finish?"))
        client.post("/focus/leave")
        assertNull(service.focusSession())
        assertNull(service.pinned())
        assertFalse(client.get("/doing").bodyAsText().contains("id=\"focus\""))
    }

    @Test
    fun `a row's menu adds a subtask, or items to a checklist, and Edit all details drafts a quick add`() = web {
        val move = service.create(Task(title = "House move", isStarred = true))
        val groceries = service.create(Task(title = "Groceries", type = TaskType.CHECKLIST, isStarred = true))
        client.submitForm("/tasks/${move.id}/add-subtask?mode=DOING", parameters { append("text", "book van -d fri") })
        assertEquals(move.id, service.tasks().single { it.title == "book van" }.parentId)
        client.submitForm("/tasks/${groceries.id}/add-subtask?mode=DOING", parameters { append("text", "milk, eggs") })
        assertEquals(2, service.tasks().count { it.parentId == groceries.id })

        val draft = client.get("/tasks/new?text=packing+%5Bpassport%2C+charger%5D&due=tomorrow&mode=DOING").bodyAsText()
        assertTrue(draft.contains(">packing</textarea>"))
        assertTrue(draft.contains("passport\ncharger"))
    }

    @Test
    fun `folder mode zooms every list, quick add, search and review into one folder`() = web {
        val personal = service.create(Task(type = TaskType.FOLDER, title = "Personal"))
        val work = service.create(Task(type = TaskType.FOLDER, title = "Work"))
        val clients = service.create(Task(type = TaskType.FOLDER, title = "Clients", parentId = work.id))
        service.create(Task(title = "Send the report", parentId = clients.id, isStarred = true))
        service.create(Task(title = "Call the dentist", parentId = personal.id, isStarred = true, dueDate = service.now()))
        service.create(Task(title = "Water the plants", parentId = personal.id, isStarred = true))
        client.post("/mode?folder=${work.id}")
        assertEquals(work.id, service.modeFolderId())

        val doing = client.get("/doing").bodyAsText()
        assertTrue(doing.contains("Work mode"))
        assertTrue(doing.contains("Send the report"))
        assertFalse(doing.contains("Water the plants"))
        assertTrue(doing.contains("1 due today outside Work")) // the dentist, due today, still shows up there
        assertTrue(doing.contains("In Work")) // the sidebar lists Work's own folders
        assertTrue(client.get("/all").bodyAsText().contains("Clients"))
        assertFalse(client.get("/all").bodyAsText().contains("Water the plants"))

        client.submitForm("/quickadd", parameters { append("text", "book the offsite") })
        assertEquals(work.id, service.tasks().single { it.title == "book the offsite" }.parentId)
        assertFalse(client.get("/search/results?q=the").bodyAsText().contains("Water the plants"))
        assertTrue(client.get("/search/results?q=the&everywhere=on").bodyAsText().contains("Water the plants"))
        assertTrue(client.get("/review").bodyAsText().contains("See all folders"))

        client.post("/mode")
        assertEquals(null, service.modeFolderId())
        assertTrue(client.get("/doing").bodyAsText().contains("Water the plants"))
    }

    @Test
    fun `folder mode syncs with the newest setting winning, and deleting the folder ends it`() = web {
        val work = service.create(Task(type = TaskType.FOLDER, title = "Work"))
        service.setMode(work.id)
        val stale = store.sync(SyncRequest(0, emptyList(), modeFolderId = null, modeSetAt = 1))
        assertEquals(work.id, stale.modeFolderId) // an older "no mode" from the phone loses
        store.sync(SyncRequest(0, emptyList(), modeFolderId = null, modeSetAt = stale.modeSetAt + 1))
        assertEquals(null, service.modeFolderId())
        service.setMode(work.id)
        service.delete(work.id)
        assertEquals(null, service.modeFolderId())
    }

    @Test
    fun `the editor's Remind sets a start reminder and one at a time, and the hour is a synced setting`() = web {
        val task = service.create(Task(title = "Follow up on Apoquel", startDate = service.now() + 86_400_000))
        client.submitForm("/tasks/${task.id}", parameters {
            append("base", taskFields(service.get(task.id)!!, emptySet(), emptySet()).toString())
            append("title", "Follow up on Apoquel"); append("type", "TASK"); append("startDate", "2030-01-01"); append("remindStart", "on")
            append("remindAtDate", "2030-01-02"); append("remindAtTime", "15:30")
        })
        val saved = service.get(task.id)!!
        assertTrue(saved.remindAtStart)
        assertNotNull(saved.remindAt)
        val page = client.get("/tasks/${task.id}").bodyAsText()
        assertTrue(page.substringAfter("At start").take(60), page.contains("At start · Jan 2 3:30 PM"))
        client.submitForm("/settings", parameters { append("rolloverHour", "4"); append("reminderHour", "8"); append("digest", "on") })
        assertEquals(8, store.sync(SyncRequest(0, emptyList())).reminderHour)
    }

    @Test
    fun `the editor's Priority picks high, normal or maybe`() = web {
        val task = service.create(Task(title = "Call the bank"))
        val page = client.get("/tasks/${task.id}").bodyAsText()
        assertTrue(page.contains("High !") && page.contains("name=\"priority\" value=\"normal\" checked"))
        client.submitForm("/tasks/${task.id}", parameters {
            append("base", taskFields(service.get(task.id)!!, emptySet(), emptySet()).toString())
            append("title", "Call the bank"); append("type", "TASK"); append("priority", "high")
        })
        assertTrue(service.get(task.id)!!.isHighPriority)
    }

    @Test
    fun `a repeating task can be skipped this time, moving to its next time undone`() = web {
        val day = 24L * 60 * 60 * 1000
        val plants = service.create(Task(title = "Water plants", recurrenceType = com.kzhovn.todoapp.data.RecurrenceType.AFTER_COMPLETION, recurrenceRule = "4", startDate = service.now() - day, isStarred = true))
        assertTrue(client.get("/doing").bodyAsText().contains("Skip this time"))
        client.post("/tasks/${plants.id}/skip?mode=DOING")
        val skipped = service.get(plants.id)!!
        assertTrue(kotlin.math.abs(service.now() + 4 * day - skipped.startDate!!) < 1000) // 4 days from now (the clock runs)
        assertFalse(skipped.isComplete || skipped.isStarred)
        assertEquals(1, service.tasks().count { it.title == "Water plants" }) // moved, not copied
    }

    @Test
    fun `every new-task box takes quick add's whole syntax`() = web {
        val home = service.saveContext(com.kzhovn.todoapp.data.TaskContext(name = "home", type = com.kzhovn.todoapp.data.ContextType.PLACE, wifiSsid = "Home"), emptyList())
        val project = service.create(Task(title = "Move house", type = TaskType.PROJECT))
        client.submitForm("/tasks/${project.id}/next", parameters { append("text", "book movers @home // ask about Sundays") })
        val step = service.tasks().single { it.title == "book movers" }
        assertEquals(project.id to "ask about Sundays", step.parentId to step.notes)
        assertEquals(setOf(home.id), service.contextIdsByTask()[step.id])
        client.submitForm("/tasks/${project.id}/subtasks", parameters { append("text", "pack books -d tomorrow") })
        assertNotNull(service.tasks().single { it.title == "pack books" }.dueDate)
    }

    @Test
    fun `projects can be prerequisites and wait on them, and a waiting project holds back its steps`() = web {
        val passport = service.create(Task(title = "Renew passport"))
        val trip = service.create(Task(title = "Trip", type = TaskType.PROJECT))
        service.create(Task(title = "Book flights", parentId = trip.id))
        val pack = service.create(Task(title = "Pack"))
        // The editors offer projects in both directions.
        assertTrue(client.get("/tasks/${pack.id}/related/suggest?text=tri&kind=prerequisite").bodyAsText().contains("<span>Trip</span>"))
        assertTrue(client.get("/tasks/${passport.id}/related/suggest?text=tri&kind=dependent").bodyAsText().contains("<span>Trip</span>"))
        client.submitForm("/tasks/${trip.id}/prerequisite", parameters { append("prerequisite", passport.id.toString()) })
        client.submitForm("/tasks/${trip.id}/dependent", parameters { append("dependent", pack.id.toString()) })
        assertEquals(setOf(passport.id), service.dependsOn(trip.id))
        assertEquals(setOf(trip.id), service.dependsOn(pack.id))
        assertEquals(listOf("Renew passport"), service.active(null).map { it.title })
    }

    @Test
    fun `the editor's Related section - one add field, suggestions, move out, delete, reorder, the parent`() = web {
        val personal = service.create(Task(title = "Personal", type = TaskType.FOLDER))
        val passport = service.create(Task(title = "Renew passport", parentId = personal.id))
        val photo = service.create(Task(title = "Take photo"))
        // An existing task (a suggestion), new ones (one per pasted line), and a new prerequisite.
        assertTrue(client.get("/tasks/${passport.id}/related/suggest?text=photo&kind=subtask").bodyAsText().contains("value=\"${photo.id}\""))
        assertFalse(client.get("/tasks/${passport.id}/related/suggest?text=pers&kind=subtask").bodyAsText().contains("value=\"${personal.id}\"")) // no folders
        client.submitForm("/tasks/${passport.id}/related", parameters { append("kind", "subtask"); append("existing", photo.id.toString()) })
        client.submitForm("/tasks/${passport.id}/related", parameters { append("kind", "subtask"); append("text", "Fill in the form\nPost it -d fri") })
        client.submitForm("/tasks/${passport.id}/related", parameters { append("kind", "prerequisite"); append("text", "Pay rent") })
        fun subs() = service.tasks().filter { it.parentId == passport.id }.sortedWith(com.kzhovn.todoapp.data.TaskOrder).map { it.title }
        assertEquals(listOf("Take photo", "Fill in the form", "Post it"), subs())
        assertNotNull(service.tasks().single { it.title == "Post it" }.dueDate)
        val rent = service.tasks().single { it.title == "Pay rent" }
        assertEquals(setOf(rent.id), service.dependsOn(passport.id))
        assertEquals(personal.id, rent.parentId)
        // Prerequisites tick off from here too, but not unrelated tasks.
        client.post("/tasks/${passport.id}/subtasks/${rent.id}/toggle")
        assertTrue(service.get(rent.id)!!.isComplete)
        val other = service.create(Task(title = "Unrelated"))
        client.post("/tasks/${passport.id}/subtasks/${other.id}/toggle")
        assertFalse(service.get(other.id)!!.isComplete)
        // Reorder: Post it before Take photo.
        val post = service.tasks().single { it.title == "Post it" }
        client.submitForm("/tasks/${passport.id}/subtasks/${post.id}/move", parameters { append("anchor", photo.id.toString()); append("after", "0") })
        assertEquals(listOf("Post it", "Take photo", "Fill in the form"), subs())
        // Move out (to the folder), unlinking it too; delete.
        service.addDependency(passport.id, photo.id)
        client.post("/tasks/${passport.id}/subtasks/${photo.id}/moveout?unlink=1")
        assertEquals(personal.id, service.get(photo.id)!!.parentId)
        assertEquals(setOf(rent.id), service.dependsOn(passport.id))
        client.post("/tasks/${passport.id}/subtasks/${post.id}/delete")
        assertEquals(listOf("Fill in the form"), subs())
        // A subtask's editor: the breadcrumb.
        val form = service.tasks().single { it.title == "Fill in the form" }
        val page = client.get("/tasks/${form.id}").bodyAsText()
        assertTrue(page.substringAfter("class=\"crumbs\"").substringBefore("title-card").let { "Personal" in it && "Renew passport" in it })
    }

    @Test
    fun `waiting items - quick add, a quiet section under Doing when a check-in is due, still waiting, resolved, a date`() = web {
        client.submitForm("/quickadd", parameters { append("text", "wait roommate decides") })
        val roommate = service.tasks().single { it.title == "roommate decides" }
        assertEquals(TaskType.WAITING, roommate.type)
        val spare = service.create(Task(title = "Clear the spare room"))
        service.addDependency(spare.id, roommate.id)
        // Never in Active; it blocks its dependent; no section until a check-in is due.
        assertEquals(emptyList<String>(), service.active(null).map { it.title })
        assertFalse(client.get("/doing").bodyAsText().contains("waiting-section"))
        service.update(roommate.id) { it.copy(startDate = service.now() - 1000) }
        val doing = client.get("/doing").bodyAsText()
        assertTrue(doing.contains("waiting-section") && doing.contains("blocks 1"))
        // Still waiting hides it again (three days on, by default).
        client.post("/tasks/${roommate.id}/still-waiting?mode=DOING")
        assertFalse(client.get("/doing").bodyAsText().contains("waiting-section"))
        assertTrue(service.get(roommate.id)!!.startDate!! > service.now() + 2 * 24 * 3600_000L)
        // Resolved: its dependent comes into Active.
        client.post("/tasks/${roommate.id}/complete?mode=DOING")
        assertEquals(listOf("Clear the spare room"), service.active(null).map { it.title })
        // A dated one resolves itself once its day comes.
        val inspection = service.create(Task(title = "Landlord's inspection", type = TaskType.WAITING, dueDate = service.now() - 1000))
        service.purgeExpired()
        assertTrue(service.get(inspection.id)!!.isComplete)
    }

    @Test
    fun `the All tree dims a task whose start is still to come`() = web {
        val later = service.create(Task(title = "Later", startDate = service.now() + 86_400_000L))
        val now = service.create(Task(title = "Now"))
        val page = client.get("/all").bodyAsText()
        fun rowOf(id: Long) = Regex("""<div[^>]*class="([^"]*)"[^>]*data-id="$id"|data-id="$id"[^>]*class="([^"]*)"""").find(page)?.groupValues?.drop(1)?.joinToString(" ").orEmpty()
        assertTrue(page.contains("later"))
        assertTrue("later" !in rowOf(now.id))
        assertTrue(later.startsLater(service.now()))
    }

    @Test
    fun `checklist items take quick add's syntax, from the editor and from quick add`() = web {
        val list = service.create(Task(title = "Groceries", type = TaskType.CHECKLIST))
        client.submitForm("/tasks/${list.id}/items", parameters { append("text", "milk due tomorrow !, eggs") })
        val milk = service.tasks().single { it.title == "milk" }
        assertTrue(milk.parentId == list.id && milk.dueDate != null && milk.isHighPriority)
        assertEquals(list.id, service.tasks().single { it.title == "eggs" }.parentId)
        client.submitForm("/quickadd", parameters { append("text", "groceries: bread*") })
        assertTrue(service.tasks().single { it.title == "bread" }.let { it.parentId == list.id && it.isStarred })
    }

    @Test
    fun `the morning digest is turned off in Settings, and the newest setting wins across devices`() = web {
        assertTrue(service.digestOn())
        assertTrue(client.get("/settings").bodyAsText().contains("name=\"digest\" checked"))
        client.submitForm("/settings", parameters { append("rolloverHour", "4") }) // the box unticked
        assertFalse(service.digestOn())
        val stale = store.sync(SyncRequest(0, emptyList(), digestOn = true, digestSetAt = 1))
        assertFalse(stale.digestOn) // an older "on" from the phone loses
        store.sync(SyncRequest(0, emptyList(), digestOn = true, digestSetAt = stale.digestSetAt + 1))
        assertTrue(service.digestOn())
    }

    @Test
    fun `the web sets the day rollover hour, and beats an older one from the phone`() = web {
        store.sync(SyncRequest(0, emptyList(), rolloverHour = 6, rolloverSetAt = 1))
        assertTrue(client.get("/settings").bodyAsText().contains("<option value=\"6\" selected"))
        client.submitForm("/settings", parameters { append("rolloverHour", "3") })
        assertEquals(3, service.rolloverHour())
        val phone = store.sync(SyncRequest(0, emptyList(), rolloverHour = 6, rolloverSetAt = 1))
        assertEquals(3 to 3, service.rolloverHour() to phone.rolloverHour)
    }

    @Test
    fun `Enter in the editor saves, never deletes, and a start time alone is today's`() = web {
        val task = service.create(Task(title = "call mom"))
        // Enter "clicks" the form's first submit button: it must be the Save one, not Delete (below the form).
        val page = client.get("/tasks/${task.id}?mode=DOING").bodyAsText()
        val editor = page.substringAfter("class=\"editor\"")
        assertTrue(editor.substringAfter("<button").substringBefore(">").contains("default-submit"))
        assertTrue(editor.indexOf("default-submit") < editor.indexOf("class=\"delete\""))

        client.submitForm("/tasks/${task.id}/autosave", parameters {
            append("base", taskFields(task, emptySet(), emptySet()).toString())
            append("title", "call mom"); append("type", "TASK"); append("startTime", "09:30")
        })
        val start = service.get(task.id)!!.startDate!!
        assertEquals(java.time.LocalDate.now().atTime(9, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), start)
    }

    @Test
    fun `the focus picker starts with Doing, and its search reaches any open task`() = web {
        service.create(Task(title = "Starred one", isStarred = true))
        service.create(Task(title = "Someday thing?", isMaybe = true))
        val picker = client.get("/focus").bodyAsText()
        assertTrue(picker.contains("Starred one"))
        assertFalse(picker.contains("Someday thing"))
        assertTrue(picker.contains("Search all tasks"))
        val found = client.get("/focus/search?q=someday").bodyAsText()
        assertTrue(found.contains("id=\"focus-cands\""))
        assertTrue(found.contains("Someday thing?"))
        assertTrue(client.get("/focus/search?q=").bodyAsText().contains("Starred one"))
    }

    @Test
    fun `focus on a checklist lists its items to tick off`() = web {
        val groceries = service.create(Task(title = "Groceries", type = TaskType.CHECKLIST))
        service.quickAdd("groceries: milk, eggs", fromDoing = false)
        val milk = service.tasks().single { it.title == "milk" }
        service.focus(groceries.id)
        assertTrue(client.get("/doing").bodyAsText().contains("/focus/item?task=${milk.id}"))
        assertTrue(client.post("/focus/item?task=${milk.id}").bodyAsText().contains("focus-item done"))
        assertTrue(service.get(milk.id)!!.isComplete)
    }

    @Test
    fun `the web timer is the current task's, shared, and stopping it unpins`() = web {
        val work = service.create(Task(title = "Ticket work", durationMinutes = 60))
        val started = client.post("/timer/start?task=${work.id}").bodyAsText()
        assertTrue(started.contains("\"id\":\"${work.id}\""))
        assertEquals(work.id, service.pinned()?.id) // starting a timer pins
        assertNotNull(service.get(work.id)!!.timerEndsAt)
        client.post("/timer/pause")
        assertNotNull(service.get(work.id)!!.timerRemaining)
        client.post("/timer/stop")
        assertNull(service.pinned())
        assertTrue(client.get("/timer").bodyAsText().contains("\"timer\":null"))
    }
}
