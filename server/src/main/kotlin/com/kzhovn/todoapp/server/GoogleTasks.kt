package com.kzhovn.todoapp.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Hands-free adds: "Hey Google, add call mom due Friday to my tasks" puts it in Google Tasks, and this
// moves it into Raspberry through quick add (docs/superpowers/specs/2026-09-28-android-auto-design.md).
// Every item on the list is taken: the list is only an inbox.

@Serializable
data class GoogleTask(val id: String, val title: String = "", val notes: String? = null, val due: String? = null)

class GoogleTasksImport(
    private val service: TaskService,
    private val store: Store,
    private val list: () -> List<GoogleTask>,
    private val delete: (String) -> Unit
) {
    // Returns how many were added. An item's id is recorded before it's deleted from Google Tasks, so
    // a failed delete leaves it to be deleted next time, never added twice.
    fun importOnce(): Int = list().count { item ->
        val key = "gtask:${item.id}"
        val new = store.getValue(key) == null && item.title.isNotBlank()
        if (new) {
            store.transaction {
                add(item)
                store.setValue(key, service.now().toString())
            }
        }
        delete(item.id)
        new
    }

    // Like a bare Discord add: into Personal, and starred so it lands in Doing, unless the words give it
    // a place or a date. Google's own due date (date only) fills in when the words didn't say.
    private fun add(item: GoogleTask) {
        val text = item.title + item.notes?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        val add = service.planQuickAdd(text)
        val task = add.task
        if (task == null) {
            service.add(add, defaultParent = null)
            return
        }
        val due = task.dueDate ?: item.due?.let { day(it) }
        val bare = task.parentId == null && task.startDate == null && due == null && !task.isMaybe
        service.add(add.copy(task = task.copy(dueDate = due)), service.defaultFolderId(), star = bare)
    }

    // "2026-10-02T00:00:00.000Z": Google Tasks keeps only the date.
    private fun day(due: String): Long? = runCatching {
        LocalDate.parse(due.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    companion object {
        // Checks the list every minute. Logged, so a broken connection shows in the journal.
        fun start(service: TaskService, store: Store, api: GoogleTasksApi) {
            val import = GoogleTasksImport(service, store, api::list, api::delete)
            println("Google Tasks import on: checking every minute")
            Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
                runCatching { import.importOnce() }
                    .onSuccess { if (it > 0) println("Google Tasks: added $it") }
                    .onFailure { println("Google Tasks import failed: ${it.message}") }
            }, 10, 60, TimeUnit.SECONDS)
        }
    }
}

// The Google Tasks API, as the one person who set it up (a refresh token from tools/google_tasks_auth.py).
class GoogleTasksApi(private val clientId: String, private val clientSecret: String, private val refreshToken: String, private val listId: String = "@default") {
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private var accessToken: String? = null
    private var expiresAt = 0L

    @Serializable private data class Token(val access_token: String, val expires_in: Long)
    @Serializable private data class Tasks(val items: List<GoogleTask> = emptyList())

    fun list(): List<GoogleTask> =
        json.decodeFromString<Tasks>(send(HttpRequest.newBuilder(URI("$BASE/lists/$listId/tasks?showCompleted=false&maxResults=100")).GET())).items

    fun delete(id: String) {
        send(HttpRequest.newBuilder(URI("$BASE/lists/$listId/tasks/$id")).DELETE())
    }

    private fun send(request: HttpRequest.Builder): String {
        val response = http.send(request.header("Authorization", "Bearer ${token()}").build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().take(200)}")
        return response.body()
    }

    // Access tokens last an hour; the refresh token gets a new one.
    @Synchronized
    private fun token(): String {
        accessToken?.takeIf { System.currentTimeMillis() < expiresAt }?.let { return it }
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        val form = "client_id=${enc(clientId)}&client_secret=${enc(clientSecret)}&refresh_token=${enc(refreshToken)}&grant_type=refresh_token"
        val response = http.send(
            HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() != 200) throw IllegalStateException("Google sign-in failed (HTTP ${response.statusCode()}): ${response.body().take(200)}")
        val token = json.decodeFromString<Token>(response.body())
        accessToken = token.access_token
        expiresAt = System.currentTimeMillis() + (token.expires_in - 60) * 1000
        return token.access_token
    }

    private companion object {
        const val BASE = "https://tasks.googleapis.com/tasks/v1"
    }
}
