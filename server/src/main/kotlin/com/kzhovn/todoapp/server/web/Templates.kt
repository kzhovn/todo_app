package com.kzhovn.todoapp.server.web

import com.kzhovn.todoapp.data.Labels
import com.kzhovn.todoapp.data.Task
import com.kzhovn.todoapp.repository.describe
import com.kzhovn.todoapp.repository.templateSize
import com.kzhovn.todoapp.repository.tickedAtFirst
import com.kzhovn.todoapp.repository.TemplateChange
import com.kzhovn.todoapp.server.TaskService
import io.ktor.http.ContentType
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.stream.createHTML

// Templates (docs/superpowers/specs/2026-10-09-templates-design.md), the web's side, as the phone's: the
// sidebar's one Templates page, Use (from it and from New ▾), Save as template, and the Update template review.
fun Route.templateRoutes(service: TaskService) {
    get("/templates") { call.respondHtml { templatesPage(service) } }
    // A list from the template, opened in the editor.
    post("/templates/{id}/use") {
        val made = call.parameters["id"]?.toLongOrNull()?.let(service::useTemplate) ?: return@post call.respondRedirect("/templates")
        call.respondRedirect("/tasks/${made.id}?mode=${call.mode().name}")
    }
    // From the editor or a row's menu; answered with a toast only.
    post("/tasks/{id}/save-template") {
        val saved = call.parameters["id"]?.toLongOrNull()?.let(service::saveAsTemplate)
        call.respondText(createHTML().div {
            attributes["id"] = "toast"; attributes["hx-swap-oob"] = "true"
            saved?.let { classes = setOf("show"); span { +Labels.savedAsTemplate(it.title) } }
        }, ContentType.Text.Html)
    }
    // The review, shown in place under the list's title; Update writes the ticked lines.
    get("/tasks/{id}/update-template") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@get
        // ?close: Cancel, the review gone again.
        if (call.request.queryParameters["close"] != null) return@get call.respondText(createHTML().div { attributes["id"] = "template-review" }, ContentType.Text.Html)
        call.respondText(createHTML().div { templateReview(service, id) }, ContentType.Text.Html)
    }
    post("/tasks/{id}/update-template") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@post
        val p = call.receiveParameters()
        service.updateTemplate(id, p.getAll("chosen").orEmpty().toSet(), p.getAll("oneOff").orEmpty().toSet())
        call.respondText(createHTML().div { attributes["id"] = "template-review" }, ContentType.Text.Html)
    }
}

// "Checklist · 24 items · Personal", for each template.
private fun templateLines(service: TaskService): List<Pair<Task, String>> {
    val everything = service.everything()
    val byId = everything.associateBy { it.id }
    return service.templates().map { t -> t to Labels.templateSummary(t.type, templateSize(t, everything), byId[t.parentId]?.title) }
}

private fun HTML.templatesPage(service: TaskService) = shellPage(service, "Raspberry · ${Labels.TEMPLATES}", "/templates") {
    div(classes = "templates") {
        div(classes = "templates-head") {
            h1 { +Labels.TEMPLATES }
            a(href = "/tasks/new?template=1", classes = "pill") { +Labels.NEW_TEMPLATE }
        }
        val lines = templateLines(service)
        if (lines.isEmpty()) p(classes = "hint") { +Labels.NO_TEMPLATES }
        lines.forEach { (t, summary) ->
            div(classes = "template-row") {
                div(classes = "template-body") {
                    a(href = "/tasks/${t.id}", classes = "template-title") { +t.title }
                    div(classes = "hint") { +summary }
                }
                useButton(t)
            }
        }
    }
}

private fun FlowContent.useButton(t: Task, label: String = Labels.USE) = form(action = "/templates/${t.id}/use", method = kotlinx.html.FormMethod.post, classes = "inline use-template") {
    button(type = ButtonType.submit, classes = "primary") { +label }
}

// New ▾'s templates: each one makes a list.
internal fun FlowContent.fromTemplateItems(templates: List<Task>) {
    if (templates.isEmpty()) return
    div(classes = "menu-label") { +Labels.FROM_TEMPLATE }
    templates.forEach { t ->
        form(action = "/templates/${t.id}/use", method = kotlinx.html.FormMethod.post, classes = "inline") {
            button(type = ButtonType.submit, classes = "menu-row") { +t.title }
        }
    }
}

// Above a template's title in the editor: it's out of the lists, and Use makes a list from it.
internal fun FlowContent.templateBanner(t: Task) = div(classes = "template-banner") {
    span { +Labels.TEMPLATE_BANNER }
    button(type = ButtonType.submit, classes = "primary") { attributes["formaction"] = "/templates/${t.id}/use"; attributes["formmethod"] = "post"; +Labels.USE }
}

// Under a list's title: "From template Packing · Update template…", the review opening in place.
internal fun FlowContent.fromTemplateLine(listId: Long, template: Task) {
    div(classes = "from-template") {
        a(href = "/tasks/${template.id}") { +Labels.fromTemplate(template.title) }
        +" · "
        a(href = "#") {
            attributes["hx-get"] = "/tasks/$listId/update-template"
            attributes["hx-target"] = "#template-review"
            attributes["hx-swap"] = "outerHTML"
            +Labels.UPDATE_TEMPLATE
        }
    }
    div { attributes["id"] = "template-review" }
}

private fun DIV.templateReview(service: TaskService, listId: Long) {
    attributes["id"] = "template-review"
    classes = setOf("template-review")
    val everything = service.everything()
    val byId = everything.associateBy { it.id }
    val template = byId[listId]?.fromTemplateId?.let(byId::get) ?: return
    val changes = service.templateChanges(listId)
    div(classes = "field-label") { +Labels.updateTemplateTitle(template.title) }
    p(classes = "hint") { +(if (changes.isEmpty()) Labels.TEMPLATE_UNCHANGED else Labels.UPDATE_TEMPLATE_HINT) }
    if (changes.isEmpty()) return
    form {
        attributes["hx-post"] = "/tasks/$listId/update-template"
        attributes["hx-target"] = "#template-review"
        attributes["hx-swap"] = "outerHTML"
        changes.forEach { change ->
            val (text, tag) = describe(change, byId)
            div(classes = "review-line") {
                label {
                    checkBoxInput(name = "chosen") { value = change.key; checked = change.tickedAtFirst() }
                    +" $text "
                    span(classes = "review-tag $tag") { +tag }
                }
                if (change is TemplateChange.Added) label(classes = "once") {
                    checkBoxInput(name = "oneOff") { value = change.key }
                    +" ${Labels.JUST_THIS_TIME}"
                }
            }
        }
        div(classes = "review-actions") {
            button(type = ButtonType.button, classes = "quiet") {
                attributes["hx-get"] = "/tasks/$listId/update-template?close=1"
                attributes["hx-target"] = "#template-review"
                attributes["hx-swap"] = "outerHTML"
                +Labels.CANCEL
            }
            button(type = ButtonType.submit, classes = "primary") { +Labels.UPDATE_TEMPLATE_GO }
        }
    }
}
