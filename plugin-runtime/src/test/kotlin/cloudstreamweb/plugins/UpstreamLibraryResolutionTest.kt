package cloudstreamweb.plugins

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Gate-0 tests: prove the published upstream library artifact resolves for JVM
 * and its plugin API classes are loadable AND functional without Android.
 */
class UpstreamLibraryResolutionTest {

    @Test
    fun `plugin api classes load from upstream artifact`() {
        val basePlugin = Class.forName("com.lagradost.cloudstream3.plugins.BasePlugin")
        val annotation = Class.forName("com.lagradost.cloudstream3.plugins.CloudstreamPlugin")
        val manifest = Class.forName("com.lagradost.cloudstream3.plugins.BasePlugin\$Manifest")
        val mainApi = Class.forName("com.lagradost.cloudstream3.MainAPI")
        val holder = Class.forName("com.lagradost.cloudstream3.APIHolder")

        assertTrue(basePlugin != manifest)
        // Annotation lives in the same package as BasePlugin (shipped together in the artifact)
        assertEquals("com.lagradost.cloudstream3.plugins", annotation.packageName)

        // APIHolder.allProviders must be present (plugins register into it at load time)
        val allProvidersField = holder.getDeclaredField("allProviders")
        assertTrue(allProvidersField.type.simpleName.contains("List") || allProvidersField.isAccessible, "allProviders exists")
    }

    @Test
    fun `jackson parses a plugin manifest json the way the loader does`() {
        val mapper = jacksonObjectMapper()
        val json = """{"name":"Test Plugin","version":3,"requiresResources":false,"pluginClassName":"some.pkg.PluginClass"}"""
        val manifest = mapper.readValue(json, Class.forName("com.lagradost.cloudstream3.plugins.BasePlugin\$Manifest"))

        assertEquals("Test Plugin", manifest.javaClass.getDeclaredField("name").apply { isAccessible = true }.get(manifest))
        assertEquals("some.pkg.PluginClass", manifest.javaClass.getDeclaredField("pluginClassName").apply { isAccessible = true }.get(manifest))
        assertEquals(3, manifest.javaClass.getDeclaredField("version").apply { isAccessible = true }.get(manifest))
        manifest.javaClass.getDeclaredField("version").apply { isAccessible = true }.set(manifest, 4)
    }
}
