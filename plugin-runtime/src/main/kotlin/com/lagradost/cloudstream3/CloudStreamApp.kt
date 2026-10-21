package com.lagradost.cloudstream3

import android.content.Context

/**
 * Headless port of the app-module CloudStreamApp holder — only the member
 * community plugins reference from their (dex-inlined) settings accessors:
 * `CloudStreamApp.Companion.getContext()`. The returned context is the fake
 * instance wired to our JVM key-value store (see the DataStore stub).
 *
 * Verified against CineStream v487 bytecode:
 *   getstatic CloudStreamApp.Companion;
 *   invokevirtual CloudStreamApp$Companion.getContext:()Landroid/content/Context;
 */
open class CloudStreamApp {
    companion object {
        /** Upstream declares this with a private set; headless it is constant. */
        var context: Context? = com.lagradost.cloudstream3.utils.DataStore.headlessContext
            private set

        /**
         * Companion write path used by community plugins' Settings
         * (descriptor erased to (String, Object)): mirror into the shared
         * fake preferences so repeated reads observe previous writes.
         */
        @JvmStatic
        fun <T> setKey(path: String?, value: T) {
            cloudstreamweb.plugins.FakeSharedPreferences.put(path, value)
        }
    }
}
