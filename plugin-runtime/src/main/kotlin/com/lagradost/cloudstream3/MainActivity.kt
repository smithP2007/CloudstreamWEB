package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.Event

/**
 * Headless port of the app-module MainActivity — community plugins touch the
 * companion surface for lifecycle events and toasts. Verified references
 * (CineStream v487 load path):
 *
 *   MainActivity$Companion.getReloadHomeEvent(): Event
 *
 * Everything Android (activities, intents, UI) simply does not exist
 * headless; events fire no observers, toasts go to stderr.
 */
open class MainActivity {
    companion object {
        @JvmStatic
        val reloadHomeEvent = Event<Boolean>()

        /** Occasional pattern: MainActivity.showToast(...) from plugin code. */
        @JvmStatic
        fun showToast(msg: String?, duration: Int = 0) {
            if (msg != null) System.err.println("[toast] $msg")
        }
    }
}
