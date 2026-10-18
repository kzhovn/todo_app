package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.completeQuestion
import kotlinx.html.label
import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import kotlinx.html.DIV
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.classes
import kotlinx.html.id
import kotlinx.html.span
import kotlinx.html.title

// The toasts (#toast), swapped in out-of-band after an action.

fun DIV.deletedToastContents(task: Task, q: String) {
    classes = setOf("show")
    span { +Labels.deleted(task.title) }
    button {
        hx("post", "/tasks/${task.id}/restore?mode=$q")
        +Labels.UNDO
    }
}

// Asks what to do with a task's open subtasks before completing it. `url` is the completing
// request; the choice is added to it.
fun DIV.askSubtasksToast(task: Task, open: Int, url: String, target: String) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    val question = completeQuestion(task, open) ?: return
    classes = setOf("show", "question")
    span { +"Complete “${task.title}”? ${question.text}" }
    val sep = if ('?' in url) '&' else '?'
    listOf("complete" to question.completeAll, "promote" to question.moveOut).forEach { (choice, label) ->
        button {
            hx("post", "$url${sep}subtasks=$choice", target)
            +label
        }
    }
    button(classes = "dismiss") { +question.cancel }
}

fun DIV.pinnedToastContents(task: Task) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    classes = setOf("show")
    span { +"Pinned “${task.title}”" }
}

// Confirms a quick add made from a page without a list to show it in.
fun DIV.addedToastContents(task: Task) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    classes = setOf("show")
    span { +"Added “${task.title}”" }
}

// Shown out-of-band after completing, with a one-click undo.
fun DIV.undoToastContents(task: Task, q: String) {
    id = "toast"
    attributes["hx-swap-oob"] = "true"
    classes = setOf("show")
    span { +"Completed “${task.title}”" }
    button {
        hx("post", "/tasks/${task.id}/uncomplete?mode=$q")
        +Labels.UNDO
    }
}
