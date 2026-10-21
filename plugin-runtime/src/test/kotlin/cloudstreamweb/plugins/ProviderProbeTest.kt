package cloudstreamweb.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.plugins.BasePlugin
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Direct probe of a real installed plugin's provider — used while wiring the
 * content endpoints (search/detail/streams) to observe returned shapes.
 * Reads the plugin ALREADY installed by the server (no network fetch here).
 */
/** Mirrors DailymotionProvider.VideoSearchResponse shape (Jackson annotations). */
data class DmSearch(@com.fasterxml.jackson.annotation.JsonProperty("list") val list: List<DmItem>)

data class DmItem(
    @com.fasterxml.jackson.annotation.JsonProperty("id") val id: String,
    @com.fasterxml.jackson.annotation.JsonProperty("title") val title: String,
    @com.fasterxml.jackson.annotation.JsonProperty("thumbnail_360_url") val thumbnail: String,
)

class ProviderProbeTest {

    @Test
    fun `probe youtube provider search`() = kotlinx.coroutines.runBlocking {
        cloudstreamweb.plugins.NewPipeBridge.initOnce()
        val cs3 = Path.of(System.getProperty("user.home"), ".cloudstream-web/Extensions/raw.githubusercontent.com/YoutubeProvider-1.cs3")
        Assumptions.assumeTrue(Files.exists(cs3), "installed youtube plugin not present")
        val cache = Path.of(System.getProperty("user.home"), ".cloudstream-web/cache/plugins/YoutubeProvider-1")
        val jar = cache.resolve("classes.jar")
        if (!Files.exists(jar)) {
            val extracted = Converter.extractCs3(cs3, cache)
            Converter.dexToJar(extracted.dexFiles.first(), jar)
        }

        val loader = ChildFirstLoader(listOf(jar.toUri().toURL()), ProviderProbeTest::class.java.classLoader)
        val manifest = ZipFile(cs3.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText()
        }
        val m = Class.forName("com.lagradost.cloudstream3.plugins.BasePlugin\$Manifest").getDeclaredConstructor().newInstance()
            as BasePlugin.Manifest
        val entryClass = Regex("\"pluginClassName\"\\s*:\\s*\"([^\"]+)\"").find(manifest)!!.groupValues[1]

        // do the plugin's classes carry their Jackson annotations after dex->jar?
        try {
            val inner = loader.findLocal("recloudstream.DailymotionProvider\$VideoSearchResponse")
            for (f in inner.declaredFields) {
                System.err.println("PROBE field ${f.name} annotations=${f.annotations.map { it.annotationClass.qualifiedName }}")
            }
            val itemCls = loader.findLocal("recloudstream.DailymotionProvider\$VideoItem")
            for (f in itemCls.declaredFields) {
                System.err.println("PROBE field ${f.name} annotations=${f.annotations.map { it.annotationClass.qualifiedName }}")
            }
        } catch (t: Throwable) {
            System.err.println("PROBE annotation introspection threw $t")
        }

        System.err.println("PROBE loader urls=" + loader.urLs.joinToString())
        val direct = try {
            System.err.println("PROBE findLocal=" + loader.findLocal(entryClass))
            true
        } catch (t: Throwable) {
            System.err.println("PROBE findLocal threw $t")
            false
        }

        val pluginClass = Class.forName(entryClass, true, loader)
        val plugin = pluginClass.getDeclaredConstructor().newInstance() as BasePlugin
        plugin.filename = "probe"
        plugin.load()

        val provider = APIHolder.allProviders.firstOrNull { it.sourcePlugin == "probe" }
            ?: throw IllegalStateException("provider not registered")

        // raw search surface
        // 1) does the library's own network layer work for this provider?
        try {
            val url = "https://www.youtube.com"
            val resp = com.lagradost.cloudstream3.app.get(url)
            System.err.println("PROBE app.get youtube code=${resp.code} text=${resp.text.take(80).replace('\n', ' ')}")
        } catch (t: Throwable) {
            System.err.println("PROBE app.get threw ${t.javaClass.name}: ${t.message}")
        }

        try {
            val inner = loader.findLocal("recloudstream.YoutubeProvider")
            System.err.println("PROBE installed youtube coin ok")
        } catch (_: Throwable) {}

        val search = try {            provider.search("documentary", 1)
        } catch (t: Throwable) {
            System.err.println("search(2-arg) threw ${t.javaClass.name}: ${t.message}")
            t.printStackTrace()
            null
        }
        if (search != null) {
            println("SearchResponseList items=${search.items.size} hasNext=${search.hasNext}")
            search.items.take(4).forEach {
                println(" - name=[${it.name}] url=[${it.url}] type=${it.type} poster=${it.posterUrl} apiName=${it.apiName}")
            }
        }

        // direct single-arg search too
        try {
            val r2 = provider.search("documentary")
            println("search(1-arg) -> ${r2?.size} items")
        } catch (t: Throwable) {
            println("search(1-arg) threw ${t.javaClass.name}: ${t.message}")
        }
    }
}
