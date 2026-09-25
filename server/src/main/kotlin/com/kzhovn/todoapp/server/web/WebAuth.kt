package com.kzhovn.todoapp.server.web

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.security.MessageDigest

private const val SESSION_DAYS = 30L
private const val SESSION_MS = SESSION_DAYS * 24 * 60 * 60 * 1000

// Signed, not encrypted: it only says "logged in since X". The issue time lets a copied cookie
// expire server-side too, not just in the browser.
@Serializable
data class WebSession(val issuedAt: Long)

class WebConfig(
    val password: String?,
    val signingKey: ByteArray,
    // Off only for local http testing; behind Caddy the site is always https.
    val secureCookies: Boolean
)

fun Application.installWebSessions(config: WebConfig) {
    install(Sessions) {
        cookie<WebSession>("raspberry_session") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = config.secureCookies
            cookie.maxAgeInSeconds = SESSION_DAYS * 24 * 60 * 60
            cookie.extensions["SameSite"] = "Strict" // also what stops cross-site form posts (CSRF)
            transform(SessionTransportTransformerMessageAuthentication(config.signingKey))
        }
    }
}

fun passwordMatches(config: WebConfig, attempt: String): Boolean {
    val expected = config.password ?: return false
    return MessageDigest.isEqual(attempt.toByteArray(), expected.toByteArray())
}

fun ApplicationCall.isLoggedIn(now: Long = System.currentTimeMillis()): Boolean =
    sessions.get<WebSession>()?.let { now - it.issuedAt < SESSION_MS } ?: false

// htmx requests can't follow a redirect into a full page, so they get told to navigate instead.
suspend fun ApplicationCall.sendToLogin() {
    if (request.header("HX-Request") != null) {
        response.header("HX-Redirect", "/login")
        respond(HttpStatusCode.OK, "")
    } else {
        respondRedirect("/login")
    }
}

// The signing key: SESSION_SECRET if set, else derived from the API token so no extra setup is needed.
fun signingKeyFrom(sessionSecret: String?, apiToken: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(("raspberry-web:" + (sessionSecret ?: apiToken)).toByteArray())
