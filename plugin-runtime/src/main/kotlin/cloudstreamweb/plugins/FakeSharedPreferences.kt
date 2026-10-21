package cloudstreamweb.plugins

import android.content.Context
import android.content.SharedPreferences

/** The single fake Context instance all headless plugins receive. */
object HeadlessSharedContext : Context()

/**
 * SharedPreferences behind the headless DataStore stub. Keys are the plugin's
 * namespaced paths; every value survives restarts via the `prefs` SQLite
 * table (write-through, type-tagged "s:"/"b:"/"i:"/"set:"). Plugins without
 * stored settings keep falling back to their own defaults.
 */
object FakeSharedPreferences : SharedPreferences {

    private val values = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun encode(value: Any?): String? = when (value) {
        is String -> "s:$value"
        is Boolean -> "b:$value"
        is Int -> "i:$value"
        is Set<*> -> "set:" + value.joinToString("\u001F") { it.toString() }
        else -> null
    }

    private fun decode(raw: String): Any? {
        val tag = raw.substringBefore(':')
        val payload = raw.substringAfter(':')
        return when (tag) {
            "s" -> payload
            "b" -> payload.toBooleanStrictOrNull()
            "i" -> payload.toIntOrNull()
            "set" -> payload.split('\u001F').toMutableSet()
            else -> null
        }
    }

    /** Load persisted plugin settings once, at first accessor use. */
    private val bootstrapped: Boolean by lazy {
        runCatching {
            cloudstreamweb.persistence.Db.loadPrefs().forEach { (k, v) ->
                decode(v)?.let { values[k] = it }
            }
            true
        }.getOrDefault(false)
    }

    /** Entry point for the CloudStreamApp.setKey companion stub. */
    fun put(key: String?, value: Any?) {
        if (key == null || value == null) return
        bootstrapped
        values[key] = value
        encode(value)?.let { encoded ->
            runCatching { cloudstreamweb.persistence.Db.savePref(key, encoded) }
        }
    }

    override fun getString(key: String?, defValue: String?): String? {
        bootstrapped
        return (key?.let { values[it] } as? String) ?: defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        bootstrapped
        return (key?.let { values[it] } as? Boolean) ?: defValue
    }

    override fun getInt(key: String?, defValue: Int): Int {
        bootstrapped
        return (key?.let { values[it] } as? Int) ?: defValue
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        bootstrapped
        return (key?.let { values[it] } as? Set<*>)?.mapTo(LinkedHashSet()) { it.toString() } ?: defValues
    }
}
