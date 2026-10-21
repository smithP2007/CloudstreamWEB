package com.lagradost.cloudstream3.plugins

import android.content.Context

/**
 * Headless port of the Android-app-module Plugin class (upstream app module:
 * app/src/main/java/com/lagradost/cloudstream3/plugins/Plugin.kt).
 *
 * The cs3 library jar ships BasePlugin (registration helpers, filename), but
 * community plugins are compiled against the APP subclass `: Plugin()` whose
 * load(context) is the entry point. Without this class on the classpath they
 * die with NoClassDefFoundError: com/lagradost/cloudstream3/plugins/Plugin —
 * the exact failure observed when installing CineStream / MegaProvider from
 * the user-added repos.
 *
 * Differences from upstream (headless reality):
 *  - registerVideoClickAction is not ported (needs app-module
 *    VideoClickActionHolder); no CSX plugin uses it on the load path.
 *  - resources stays null (Android resources do not exist headless) — the
 *    type is declared so plugin field assignments/inits still link.
 */
abstract class Plugin : BasePlugin() {

    /** Upstream contract: called when the plugin is loaded. */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // If not overridden by an extension then try the cross-platform load()
        load()
    }

    var resources: android.content.res.Resources? = null

    var openSettings: ((context: Context) -> Unit)? = null
}
