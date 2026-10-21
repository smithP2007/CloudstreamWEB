package com.lagradost.cloudstream3.syncproviders

/**
 * Headless port of the app-module AuthRepo/SyncRepo (SyncRepo.kt + AuthRepo.kt
 * upstream). Only construction surface + authUser is implemented; suspend
 * flows (library, status) resolve from provider request paths and report
 * "no account" headless.
 *
 * NOTE: upstream's suspend library() carries the JvmName
 * `library-IoAF18A` in compiled artifacts, which cannot be declared
 * from Kotlin/Java source — deliberately left unimplemented; community
 * providers calling it get a typed NoSuchMethodError at REQUEST time (not
 * during load), documented in docs/stub-inventory.md.
 */
open class AuthRepo(open val api: AuthAPI) {
    val idPrefix: String get() = api.idPrefix
    val name: String get() = api.name
    val requiresLogin: Boolean get() = api.requiresLogin
    val createAccountUrl: String? get() = api.createAccountUrl
    val hasOAuth2: Boolean get() = api.hasOAuth2
    val hasPin: Boolean get() = api.hasPin
    val hasInApp: Boolean get() = api.hasInApp
    val isAvailable: Boolean get() = !api.requiresLogin || authUser() != null

    fun authUser(): AuthUser? = api.authUser()
}
