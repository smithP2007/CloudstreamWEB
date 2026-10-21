package cloudstreamweb.plugins

import android.content.Context
import android.content.SharedPreferences

/** The single fake Context instance all headless plugins receive. */
object HeadlessSharedContext : Context()

/**
 * SharedPreferences behind the headless DataStore stub. Read-only today:
 * plugins' settings read chains resolve here, and every key falls back to
 * "not set" so plugins apply their own defaults (e.g. CineStream's
 * `getKey<Boolean>(...) ?: true` keeps providers enabled by default).
 *
 * Writes: plugins (e.g. "mark seen providers") can write harmlessly into
 * memory; nothing is persisted, which keeps reinstalls deterministic.
 */
object FakeSharedPreferences : SharedPreferences {

    private val values = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Entry point for the CloudStreamApp.setKey companion stub. */
    fun put(key: String?, value: Any?) {
        if (key != null && value != null) values[key] = value
    }

    override fun getString(key: String?, defValue: String?): String? =
        (key?.let { values[it] } as? String) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (key?.let { values[it] } as? Boolean) ?: defValue

    override fun getInt(key: String?, defValue: Int): Int =
        (key?.let { values[it] } as? Int) ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (key?.let { values[it] } as? Set<*>)?.mapTo(LinkedHashSet()) { it.toString() } ?: defValues
}
