package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.server.web.webRoutes
import com.kzhovn.todoapp.sync.SyncJson
import com.kzhovn.todoapp.sync.SyncRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest

fun main() {
    val env = System.getenv()
    val store = Store(env["DB_PATH"] ?: "todo.db")
    val apiToken = env["API_TOKEN"] ?: error("API_TOKEN is required")
    val port = env["PORT"]?.toInt() ?: 8443
    val service = TaskService(store)
    // The web pages have no login of their own; Caddy's basic_auth provides it. So they're only
    // served when the server listens on loopback, reachable solely through Caddy. Exposed directly,
    // the pages don't exist (fail closed) and only the token-protected /sync is served.
    val webEnabled = env["HOST"] in setOf("127.0.0.1", "localhost", "::1")

    env["BOT_TOKEN"]?.let { botToken ->
        Bot.start(
            botToken, service, store,
            // Everyone listed shares the one task list; the data model is single-user.
            allowedUserIds = env["ALLOWED_USER_IDS"]?.split(',')?.map { it.trim().toLong() }?.toSet()
                ?: error("ALLOWED_USER_IDS (comma-separated Discord user ids) is required with BOT_TOKEN"),
            digestChannelId = env["DIGEST_CHANNEL_ID"]?.toLong(),
            digestTime = env["DIGEST_TIME"]
        )
    }

    embeddedServer(Netty, applicationEnvironment(), {
        val keystorePath = env["KEYSTORE_PATH"]
        if (keystorePath == null) {
            // Plain HTTP: local tests, or behind Caddy (then HOST=127.0.0.1 keeps it off the network).
            connector {
                this.port = port
                env["HOST"]?.let { this.host = it }
            }
        } else {
            val password = (env["KEYSTORE_PASSWORD"] ?: error("KEYSTORE_PASSWORD is required")).toCharArray()
            val keyStore = KeyStore.getInstance(File(keystorePath), password)
            sslConnector(keyStore, env["KEY_ALIAS"] ?: "todo", { password }, { password }) {
                this.port = port
                this.keyStorePath = File(keystorePath)
            }
        }
    }) { api(store, apiToken, service, webEnabled) }.start(wait = true)
}

fun Application.api(store: Store, apiToken: String, service: TaskService = TaskService(store), webEnabled: Boolean = false) {
    install(ContentNegotiation) { json(SyncJson) }
    // Only this site's own scripts may run (no inline script), it can't be framed by other sites, and
    // browsers are told to stay on https.
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "same-origin")
        call.response.header("Strict-Transport-Security", "max-age=31536000")
        // Browsers attach basic-auth credentials to any site's form posts, so a web post must come
        // from this site. They always send Origin on cross-site posts.
        if (call.request.httpMethod == HttpMethod.Post && call.request.path() != "/sync") {
            val origin = call.request.headers[HttpHeaders.Origin]
            val originHost = origin?.let { runCatching { URI(it).authority }.getOrNull() ?: "" } // "null" origin: never ours
            if (originHost != null && originHost != call.request.headers[HttpHeaders.Host]) {
                call.respond(HttpStatusCode.Forbidden)
                return@intercept finish()
            }
        }
    }
    val expected = "Bearer $apiToken".toByteArray()
    routing {
        if (webEnabled) webRoutes(service)
        post("/sync") {
            val auth = call.request.headers["Authorization"].orEmpty().toByteArray()
            if (!MessageDigest.isEqual(auth, expected)) return@post call.respond(HttpStatusCode.Unauthorized)
            call.respond(store.sync(call.receive<SyncRequest>()))
        }
    }
}
