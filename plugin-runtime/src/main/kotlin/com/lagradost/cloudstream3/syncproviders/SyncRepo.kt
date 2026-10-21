package com.lagradost.cloudstream3.syncproviders

/** Stateless safe abstraction of SyncAPI (upstream SyncRepo.kt, headless). */
open class SyncRepo(override val api: SyncAPI) : AuthRepo(api) {
    val syncIdName: SyncIdName? get() = api.syncIdName
    var requireLibraryRefresh: Boolean
        get() = api.requireLibraryRefresh
        set(value) {
            api.requireLibraryRefresh = value
        }
}
