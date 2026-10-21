package com.lagradost.cloudstream3.utils

import android.content.Context

/**
 * Headless port of the app-module UiText (upstream
 * app/src/main/java/com/lagradost/cloudstream3/utils/TextUtil.kt).
 *
 * Community providers call UiText.asString(context) while formatting library
 * list names; resources don't exist headless, so anything string-backed
 * resolves to its text and resource-backed variants fall back to a key.
 */
sealed class UiText {
    data class DynamicString(val value: String) : UiText() {
        override fun asStringNull(context: Context?): String? = value
        override fun hashCode(): Int = value.hashCode()
        override fun equals(other: Any?): Boolean =
            other is DynamicString && other.value == value
    }

    class StringResource(
        val resId: Int,
        val args: Array<out Any?> = emptyArray(),
    ) : UiText() {
        override fun asStringNull(context: Context?): String? = "[res=$resId]"
    }

    abstract fun asStringNull(context: Context?): String?

    fun asString(context: Context?): String = asStringNull(context) ?: ""
}
