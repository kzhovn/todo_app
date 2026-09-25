package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.sync.taskFields
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(client.get("/doing").bodyAsText().contains("icon sub"))
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
        val a = service.create(Task(title = "A", parentId = folder.id))
        service.create(Task(title = "B", parentId = folder.id))
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
}
