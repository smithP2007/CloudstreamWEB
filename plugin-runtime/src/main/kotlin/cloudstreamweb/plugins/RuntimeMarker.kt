package cloudstreamweb.plugins

/**
 * Phase 0 placeholder for the plugin runtime (loader, converter, registry).
 * Phase 2 replaces this file with:
 *  - Loader.kt (URLClassLoader chain + manifest.json entry discovery)
 *  - Converter.kt (classes.dex -> jar subprocess)
 *  - Registry.kt (provider registry)
 *  - Api.kt (source-ported Plugin/BasePlugin-compat API)
 */
object RuntimeMarker {
    const val VERSION = "0.1.0"
}
