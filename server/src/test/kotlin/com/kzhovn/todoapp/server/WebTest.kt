package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.server.web.WebConfig
import io.ktor.client.plugins.cookies.HttpCookies
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
    private val config = WebConfig(password = "hunter2", signingKey = ByteArray(32) { it.toByte() }, secureCookies = false)

    private fun web(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { api(store, "token", service, config) }
        block()
    }

    private suspend fun ApplicationTestBuilder.loggedInClient() = createClient {
        install(HttpCookies)
        followRedirects = false
    }.also { it.submitForm("/login", parameters { append("password", "hunter2") }) }

    @Test
    fun `pages need a login, and a wrong password is refused`() = web {
        val anon = createClient { followRedirects = false }
        assertEquals("/login", anon.get("/doing").headers["Location"])
        assertEquals(HttpStatusCode.Unauthorized, anon.submitForm("/login", parameters { append("password", "nope") }).status)

        val client = loggedInClient()
        assertEquals(HttpStatusCode.OK, client.get("/doing").status)
        assertEquals(HttpStatusCode.OK, client.get("/static/htmx/htmx.min.js").status)
    }

    @Test
    fun `quick add lands in Personal, and completing shows an undo toast`() = web {
        val personal = service.create(Task(type = TaskType.FOLDER, title = "Personal"))
        val client = loggedInClient()

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
        val all = loggedInClient().get("/all").bodyAsText()
        val title = all.indexOf("Wool coat")
        val projectRow = all.substring(all.lastIndexOf("class=\"row", title), title)
        assertTrue(projectRow.contains("class=\"project\""))
        assertFalse(projectRow.contains("class=\"check"))
        assertTrue(loggedInClient().get("/doing").bodyAsText().contains("↳"))
    }
}
