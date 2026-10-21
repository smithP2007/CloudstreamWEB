package com.lagradost.cloudstream3

import android.app.Activity

/**
 * Headless port of the app-module CommonActivity holder (verified usage:
 * `CommonActivity.INSTANCE.getActivity(): Activity` inside CineSimklProvider).
 * There is no foreground activity headless — getActivity() returns null,
 * which every toast/showDialog helper in cs3 handles gracefully.
 */
object CommonActivity {
    @JvmStatic
    fun getActivity(): Activity? = null
}
