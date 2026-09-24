// Pure JVM logic shared by :app and :server. Versions are pinned to ones built for Kotlin 2.0.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(17) }

dependencies {
    // Entity annotations only; :app's Room compiler reads them, :server ignores them.
    compileOnly("androidx.room:room-common:2.6.1")
    api("org.dmfs:lib-recur:0.17.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
