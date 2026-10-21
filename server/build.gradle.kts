plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":plugin-runtime"))

    // Declared directly because routes reference APIHolder/MainAPI themselves
    implementation(libs.cs.library)

    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("io.ktor:ktor-server-status-pages")

    // Transitively present via :plugin-runtime (cs-library etc.), declared
    // explicitly because the server touches okhttp and jackson directly too.
    implementation(libs.okhttp)
    implementation(libs.jackson)
    implementation(libs.coroutines.core)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.junit.jupiter)
    testImplementation("io.ktor:ktor-server-test-host") // version from ktor-bom
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "cloudstreamweb.ApplicationKt"
}

tasks.test {
    useJUnitPlatform()
    // Isolate the SQLite store for library/Downloads tests: RuntimeConfig reads
    // csweb.dataDir first, so the developer's ~/.cloudstream-web stays untouched.
    systemProperty(
        "csweb.dataDir",
        layout.buildDirectory.dir("test-data/csweb").get().asFile.absolutePath,
    )
}
