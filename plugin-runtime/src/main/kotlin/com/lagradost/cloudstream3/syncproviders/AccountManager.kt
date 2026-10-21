package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.SimklApi

/**
 * Headless port of the app-module AccountManager — community providers fetch
 * their sync api handles through companion accessors (verified:
 * AccountManager$Companion.getSimklApi(): providers/SimklApi). Every handle
 * points at a headless "not logged in" implementation.
 *
 * Upstream declares these as an `object` with an inner companion access layer;
 * a Kotlin `object` cannot itself contain a companion, so the class is
 * manually shaped: static COMPANION field + getSimklApi instance method
 * (exactly resolving the dex shape `getstatic AccountManager.Companion;
 * invokevirtual Companion.getSimklApi()`).
 */
open class AccountManager {

    private constructor()

    companion object {
        /** Upstream initializes these eagerly as vals. */
        val simklApi: SimklApi = SimklApi()
    }
}
