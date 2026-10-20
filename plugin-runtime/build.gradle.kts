plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The upstream CloudStream plugin API surface (BasePlugin, CloudstreamPlugin,
    // MainAPI, ExtractorApi, APIHolder...). Resolves the JVM variant of the
    // Kotlin Multiplatform artifact via Gradle Module Metadata.
    implementation(libs.cs.library)

    // Same versions as official extension builds (see upstream extension template);
    // these end up on our classpath so plugin bytecode can link against them.
    implementation(libs.jackson)
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencies {
    // ... existing deps above (kept at module top level)
}

tasks.test {
    useJUnitPlatform()
}
