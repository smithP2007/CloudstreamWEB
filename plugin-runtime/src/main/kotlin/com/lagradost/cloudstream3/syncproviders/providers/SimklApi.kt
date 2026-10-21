package com.lagradost.cloudstream3.syncproviders.providers

/** "String hold both accounts?" — headless not-logged-in Simkl API stub. */
open class SimklApi : com.lagradost.cloudstream3.syncproviders.SyncAPI() {
    override val name: String = "Simkl"
    override val idPrefix: String = "simkl"
    override val mainUrl: String = "https://simkl.com"
}
