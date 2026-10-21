package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import cloudstreamweb.plugins.FakeSharedPreferences

/**
 * Headless port of the app-module DataStore — only the member community
 * plugins execute: `DataStore.getSharedPrefs(context): SharedPreferences`
 * (the inlined cs3 settings-read chain lands in SharedPreferences.getString).
 *
 * The returned store is process-wide (not per-context) and simply backed by
 * an immutable default map so repeated installs see consistent settings.
 */
object DataStore {

    /** The one fake context handed to every plugin's load(context). */
    val headlessContext: Context = cloudstreamweb.plugins.HeadlessSharedContext

    @Volatile
    private var store: SharedPreferences = FakeSharedPreferences

    fun getSharedPrefs(context: Context?): SharedPreferences = store

    /** Human caches a preferences swap (unused today, kept symmetrical). */
    fun setStore(prefs: SharedPreferences?) {
        if (prefs != null) store = prefs
    }
}
