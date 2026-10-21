package cloudstreamweb.plugins

import java.net.URL
import java.net.URLClassLoader

/**
 * Child-first URLClassLoader: plugin-bundled classes win over parent classes,
 * while com.lagradost/kotlin/okhttp classes resolve via the parent (the server
 * runtime with the upstream library artifact on the classpath).
 */
class ChildFirstLoader(
    urls: List<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls.toTypedArray(), parent) {

    /** Test-visible local lookup (protected findClass wrapper). */
    fun findLocal(name: String): Class<*> = findClass(name)

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        val loaded = findLoadedClass(name)
        if (loaded != null) return loaded

        val selfFound = try {
            findClass(name)
        } catch (cnfe: ClassNotFoundException) {
            null
        }
        val resolved = selfFound ?: parent.loadClass(name)
        if (resolve) resolveClass(resolved)
        return resolved
    }
}
