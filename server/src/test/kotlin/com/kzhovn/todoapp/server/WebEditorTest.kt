package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.sync.SyncRequest
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.data.checklistItems
import com.kzhovn.todoapp.data.TaskType
import com.kzhovn.todoapp.sync.taskFields
import com.kzhovn.todoapp.server.web.dateTime
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
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

// The web's task editor: its form, the Related section, and the fields it saves.
class WebEditorTest {
    private val store = Store(File.createTempFile("web", ".db").apply { deleteOnExit() }.path)
    private val service = TaskService(store)
    private fun web(enabled: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { api(store, "token", service, webEnabled = enabled) }
        block()
    }

    @Test
    fun `a checklist's sections show in its editor, take items, and an item drags into one`() = web {
        service.quickAdd("packing [passport; clothes: socks]", fromDoing = false)
        val list = service.tasks().single { it.type == TaskType.CHECKLIST }
        val clothes = service.tasks().single { it.type == TaskType.SECTION }
        val passport = service.tasks().single { it.title == "passport" }
        val editor = client.get("/tasks/${list.id}").bodyAsText()
        assertTrue(editor.contains(">clothes<") && editor.contains("+ Add to clothes"))
        client.submitForm("/tasks/${list.id}/items?in=${clothes.id}", parameters { append("text", "shirt") })
        assertEquals(clothes.id, service.tasks().single { it.title == "shirt" }.parentId)
        client.submitForm("/tasks/${list.id}/subtasks/${passport.id}/move", parameters { append("anchor", "${clothes.id}"); append("after", "1") })
        assertEquals(clothes.id, service.get(passport.id)!!.parentId)
        assertEquals(listOf("passport", "socks", "shirt"), checklistItems(list.id, service.tasks()).map { it.title })
    }

    @Test
    fun `ticking a subtask with open subtasks in the editor asks first`() = web {
        val parent = service.create(Task(title = "Trip"))
        val sub = service.create(Task(title = "Buy canisters", parentId = parent.id))
        val step = service.create(Task(title = "Check size", parentId = sub.id))
        val asked = client.post("/tasks/${parent.id}/subtasks/${sub.id}/toggle").bodyAsText()
        assertTrue(asked.contains("1 active subtask"))
        assertFalse(service.get(sub.id)!!.isComplete)
        client.post("/tasks/${parent.id}/subtasks/${sub.id}/toggle?subtasks=complete")
        assertTrue(service.get(sub.id)!!.isComplete && service.get(step.id)!!.isComplete)
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
    fun `a new dependent task lands in the task's folder and waits for it`() = web {
        val folder = service.create(Task(type = TaskType.FOLDER, title = "Home"))
        val paint = service.create(Task(title = "Buy paint", parentId = folder.id))
        client.submitForm("/tasks/${paint.id}/related", parameters { append("kind", "dependent"); append("text", "Paint the wall") })
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
        assertTrue(client.get("/list/doing?q=4417").bodyAsText().contains("notes-line\">Ref 4417"))
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
        client.submitForm("/tasks/${report.id}/related", parameters { append("kind", "prerequisite"); append("existing", vpn.id.toString()) })
        client.submitForm("/tasks/${report.id}/related", parameters { append("kind", "prerequisite"); append("text", "Collect numbers") })
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
    fun `a new task can be pinned from the editor, pinned when it's saved`() = web {
        assertTrue(client.get("/tasks/new?type=TASK").bodyAsText().contains("name=\"pin\""))
        client.submitForm("/tasks/new", parameters { append("title", "Write draft"); append("type", "TASK"); append("pin", "on") })
        assertEquals("Write draft", service.pinned()?.title)
        client.submitForm("/tasks/new", parameters { append("title", "Unpinned"); append("type", "TASK") })
        assertEquals("Write draft", service.pinned()?.title)
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
    fun `projects can be prerequisites and wait on them, and a waiting project holds back its steps`() = web {
        val passport = service.create(Task(title = "Renew passport"))
        val trip = service.create(Task(title = "Trip", type = TaskType.PROJECT))
        service.create(Task(title = "Book flights", parentId = trip.id))
        val pack = service.create(Task(title = "Pack"))
        // The editors offer projects in both directions.
        assertTrue(client.get("/tasks/${pack.id}/related/suggest?text=tri&kind=prerequisite").bodyAsText().contains("<span>Trip</span>"))
        assertTrue(client.get("/tasks/${passport.id}/related/suggest?text=tri&kind=dependent").bodyAsText().contains("<span>Trip</span>"))
        client.submitForm("/tasks/${trip.id}/related", parameters { append("kind", "prerequisite"); append("existing", passport.id.toString()) })
        client.submitForm("/tasks/${trip.id}/related", parameters { append("kind", "dependent"); append("existing", pack.id.toString()) })
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
    fun `a step that doesn't hold up the next shows its mark, and the row menu and editor switch it`() = web {
        client.submitForm("/quickadd", parameters { append("text", "Laundry > take wet out &> hang the rest > start dryer"); append("mode", "DOING") })
        val hang = service.tasks().single { it.title == "hang the rest" }
        assertTrue(hang.alongsideNext)
        assertTrue(client.get("/all").bodyAsText().contains("class=\"alongside\""))
        client.post("/tasks/${hang.id}/alongside?mode=ALL")
        assertFalse(service.get(hang.id)!!.alongsideNext)
        val editor = client.get("/tasks/${hang.id}?mode=ALL").bodyAsText()
        assertTrue(editor.contains(Labels.ALONGSIDE_NEXT))
        client.submitForm("/tasks/${hang.id}", parameters {
            append("title", "hang the rest"); append("type", "TASK"); append("parent", hang.parentId.toString())
            append("alongsideShown", "1"); append("alongsideNext", "on")
        })
        assertTrue(service.get(hang.id)!!.alongsideNext)
        assertTrue(client.get("/tasks/${hang.id}?mode=ALL").bodyAsText().contains(Labels.comesUpWith("start dryer")))
    }

    @Test
    fun `a waiting item's Timer is saved from its own pill`() = web {
        val wait = service.create(Task(title = "Pasta", type = TaskType.WAITING))
        client.submitForm("/tasks/${wait.id}", parameters { append("title", "Pasta"); append("type", "WAITING"); append("duration", "10"); append("duration", "") })
        assertEquals(10, service.get(wait.id)!!.durationMinutes)
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
    fun `a waiting item's check-in interval saves from the editor`() = web {
        val waiting = service.create(Task(title = "Landlord to reply", type = TaskType.WAITING))
        client.submitForm("/tasks/${waiting.id}/autosave", parameters {
            append("base", taskFields(waiting, emptySet(), emptySet()).toString())
            append("title", "Landlord to reply"); append("type", "WAITING"); append("checkInN", "2"); append("checkInUnit", "7"); append("checkInDate", "2030-05-04")
        })
        val saved = service.get(waiting.id)!!
        assertEquals(14, saved.checkInDays)
        assertEquals(dateTime("2030-05-04", null), saved.startDate)
    }
}
