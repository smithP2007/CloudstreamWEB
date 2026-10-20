package cloudstreamweb.plugins

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.plugins.BasePlugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Real-artifact smoke test: loads an actual .cs3-converted jar (Youtube official
 * plugin) on the JVM. Skips when the test artifact is absent so CI stays green.
 *
 * Expects a converted jar at /tmp/cs3/extracted/classes.jar (see Phase 2 workflow).
 */
class Cs3SmokeTest {

    @Test
    fun `loads converted youtube cs3 and registers its provider`() {
        val jar = Path.of("/tmp/cs3/extracted/classes.jar")
        Assumptions.assumeTrue(Files.exists(jar), "converted test jar not present")

        // classloader: child-first, parent = runtime (library artifact on classpath)
        val loader = ChildFirstLoader(listOf(jar.toUri().toURL()), Cs3SmokeTest::class.java.classLoader)

        ZipFile(jar.toFile()).use { zip -> /* jar sanity */ }

        // manifest from the ORIGINAL cs3 (here simulated from upstream repo manifest)
        val manifestJson = """{"requiresResources":false,"version":1,"pluginClassName":"recloudstream.YoutubePlugin","name":"YoutubeProvider"}"""
        val manifest = jacksonObjectMapper().readValue(manifestJson, BasePlugin.Manifest::class.java)
        assertEquals("recloudstream.YoutubePlugin", manifest.pluginClassName)

        val before = APIHolder.allProviders.size
        val pluginClass = Class.forName(manifest.pluginClassName, true, loader)
        assertTrue(BasePlugin::class.java.isAssignableFrom(pluginClass), "plugin must extend BasePlugin")

        val plugin = pluginClass.getDeclaredConstructor().newInstance() as BasePlugin
        plugin.filename = "youtube-cs3-test"
        plugin.load()

        val after = APIHolder.allProviders.size
        assertTrue(after > before, "load() must register a provider; before=$before after=$after")
        assertEquals("youtube-cs3-test", APIHolder.allProviders.last().sourcePlugin)

        plugin.beforeUnload()
    }
}

/**
 * Child-first URLClassLoader: plugin-bundled classes win over parent classes for
 * plugin-owned packages, while com.lagradost/android stubs resolve via parent.
 */
class ChildFirstLoader(
    urls: List<java.net.URL>,
    parent: ClassLoader,
) : URLClassLoader(urls.toTypedArray(), parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // find locally first (child-first)
        val loaded = findLoadedClass(name)
        if (loaded != null) return loaded
        val selfFound = try { findClass(name) } catch (t: ClassNotFoundException) { null }
        val resolved = selfFound ?: parent.loadClass(name)
        if (resolve) resolveClass(resolved)
        return resolved
    }
}
