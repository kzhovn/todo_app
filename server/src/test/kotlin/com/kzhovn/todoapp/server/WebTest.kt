package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
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
}
