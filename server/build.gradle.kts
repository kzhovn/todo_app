plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.kzhovn.todoapp.server.MainKt") }

dependencies {
    implementation(project(":core"))
    implementation("io.ktor:ktor-server-netty:3.6.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("io.ktor:ktor-server-html-builder:3.6.0")
    // htmx as a webjar: served from the classpath, so there's no CDN and no vendored copy to update.
    runtimeOnly("org.webjars.npm:htmx.org:4.0.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("net.dv8tion:JDA:6.7.0") { exclude(module = "opus-java") }
    implementation("org.slf4j:slf4j-simple:2.0.20")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:3.6.0")
}
