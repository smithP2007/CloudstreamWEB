package cloudstreamweb

/**
 * Startup probe: verifies the upstream CloudStream :library artifact is on the
 * classpath and its core plugin-API classes load and link on the JVM.
 * If this fails, nothing plugin-related will work, so fail loudly here.
 */
object ApiProbe {
    data class Result(val ok: Boolean, val detail: String)

    fun probe(): Result {
        return try {
            // These classes ship inside the published library artifact.
            val manifest = Class.forName("com.lagradost.cloudstream3.plugins.BasePlugin\$Manifest")
            val basePlugin = Class.forName("com.lagradost.cloudstream3.plugins.BasePlugin")
            val annotation = Class.forName("com.lagradost.cloudstream3.plugins.CloudstreamPlugin")

            val mainApi = Class.forName("com.lagradost.cloudstream3.MainAPI")
            val holder = Class.forName("com.lagradost.cloudstream3.APIHolder")

            val names = listOf(manifest, basePlugin, annotation, mainApi, holder).joinToString(",") { it.simpleName }
            Result(ok = true, detail = "loaded($names)")
        } catch (t: Throwable) {
            System.err.println("ApiProbe FAILED: $t")
            Result(ok = false, detail = "missing: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
