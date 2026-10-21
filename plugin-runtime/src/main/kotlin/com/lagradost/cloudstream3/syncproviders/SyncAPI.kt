package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType

/**
 * Headless port of the app-module syncproviders chain. Only the surface that
 * community provider CONSTRUCTION needs (verified through
 * CineSimklProvider ctor bytecode):
 *
 *   AccountManager$Companion.getSimklApi(): SimklApi
 *   SyncRepo."<init>":(SyncAPI;)V  ->  AuthRepo(api)  ->  AuthAPI()
 *
 * Deeper suspend parts resolve lazily from provider request paths; account
 * sync cannot exist headless, so everything returns "not logged in" data.
 */
data class AuthUser(
    val name: String? = null,
    val icon: String? = null,
)

data class AuthData(
    val token: com.lagradost.cloudstream3.syncproviders.AuthToken? = null,
    val user: AuthUser? = null,
)

data class AuthToken(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessTokenLifetime: Long? = null,
    val refreshTokenLifetime: Long? = null,
)

open class AuthAPI {
    open val name: String = "NONE"
    open val idPrefix: String = "none"
    open val requiresLogin: Boolean = true
    open val createAccountUrl: String? = null
    open val hasOAuth2: Boolean = false
    open val hasPin: Boolean = false
    open val hasInApp: Boolean = true
    open val isAvailable: Boolean get() = authUser() != null

    open fun authUser(): AuthUser? = null
}

open class SyncAPI : AuthAPI() {
    open var requireLibraryRefresh: Boolean = true
    open val mainUrl: String = "NONE"
    open val syncIdName: SyncIdName? = null

    class AbstractSyncStatus(
        val status: Int? = null,
        val score: Int? = null,
        val episodesWatched: Int? = null,
    )

    /** Upstream shapes (app SyncAPI.kt) referenced while walking a library. */
    data class LibraryMetadata(
        val allLibraryLists: List<LibraryList> = emptyList(),
    )

    data class LibraryList(
        val name: com.lagradost.cloudstream3.utils.UiText? = null,
        val items: List<LibraryItem> = emptyList(),
    )

    data class LibraryItem(
        val name: String,
        val url: String,
        val syncId: String,
        val episodesCompleted: Int?,
        val episodesTotal: Int?,
        val personalRating: Score?,
        val lastUpdatedUnixTime: Long?,
        val apiName: String,
        val type: TvType?,
        val posterUrl: String?,
        val posterHeaders: Map<String, String>?,
        val quality: SearchQuality?,
        val releaseDate: java.util.Date?,
        val id: Int? = null,
        val plot: String? = null,
        val score: Score? = null,
        val tags: List<String>? = null,
    )
}
