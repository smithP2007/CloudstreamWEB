package android.content

/**
 * Minimal android.content.SharedPreferences stub. Community .cs3 plugins
 * inline cs3's DataStore.getKey into their dex, which ends in
 * SharedPreferences.getString(...). Only the members actually executed by
 * their load paths are declared — NoSuchMethodError surfaces typed load
 * failures for anything exotic (iterate per evidence).
 */
interface SharedPreferences {
    fun getString(key: String?, defValue: String?): String?

    fun getBoolean(key: String?, defValue: Boolean): Boolean

    fun getInt(key: String?, defValue: Int): Int

    fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>?
}
