package com.kzhovn.todoapp.server

import com.kzhovn.todoapp.sync.SyncJson
import com.kzhovn.todoapp.sync.SyncRequest
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest

fun main() {
    val env = System.getenv()
    val store = Store(env["DB_PATH"] ?: "todo.db")
    val apiToken = env["API_TOKEN"] ?: error("API_TOKEN is required")
    val port = env["PORT"]?.toInt() ?: 8443

    env["BOT_TOKEN"]?.let { botToken ->
        Bot.start(
            botToken, TaskService(store), store,
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
            // Plain HTTP only for local tests; production always sets KEYSTORE_PATH.
            connector { this.port = port }
        } else {
            val password = (env["KEYSTORE_PASSWORD"] ?: error("KEYSTORE_PASSWORD is required")).toCharArray()
            val keyStore = KeyStore.getInstance(File(keystorePath), password)
            sslConnector(keyStore, env["KEY_ALIAS"] ?: "todo", { password }, { password }) {
                this.port = port
                this.keyStorePath = File(keystorePath)
            }
        }
    }) { api(store, apiToken) }.start(wait = true)
}

fun Application.api(store: Store, apiToken: String) {
    install(ContentNegotiation) { json(SyncJson) }
    val expected = "Bearer $apiToken".toByteArray()
    routing {
        post("/sync") {
            val auth = call.request.headers["Authorization"].orEmpty().toByteArray()
            if (!MessageDigest.isEqual(auth, expected)) return@post call.respond(HttpStatusCode.Unauthorized)
            call.respond(store.sync(call.receive<SyncRequest>()))
        }
    }
}
