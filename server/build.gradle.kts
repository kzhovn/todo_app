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
    // Its one-file bundle has the plugins in it already.
    runtimeOnly("org.webjars.npm:fullcalendar:7.1.0") { isTransitive = false }
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("net.dv8tion:JDA:6.7.0") { exclude(module = "opus-java") }
    implementation("org.slf4j:slf4j-simple:2.0.20")

    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":core")))
    testImplementation("io.ktor:ktor-server-test-host:3.6.0")
}

// The benchmark harness (src/test/.../bench/Bench.kt): ./gradlew :server:bench --args="3000 10000", or tools/bench.sh.
tasks.register<JavaExec>("bench") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.kzhovn.todoapp.server.bench.BenchKt")
    jvmArgs("-Xmx1g")
    // BENCH_PROFILE=out.jfr records a Java Flight Recorder profile of the run (read it with `jfr print`).
    System.getenv("BENCH_PROFILE")?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
}
