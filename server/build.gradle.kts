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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "cloudstreamweb.ApplicationKt"
}

tasks.test {
    useJUnitPlatform()
}
