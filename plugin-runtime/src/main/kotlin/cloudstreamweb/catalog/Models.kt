package cloudstreamweb.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Repo manifest (repo.json) — schema verified from upstream
 * app/src/main/java/com/lagradost/cloudstream3/plugins/RepositoryManager.kt
 */
@Serializable
data class RepoManifest(
    @SerialName("iconUrl") val iconUrl: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("manifestVersion") val manifestVersion: Int = 1,
    @SerialName("pluginLists") val pluginLists: List<String> = emptyList(),
)

/** One entry of a repo's plugins.json (upstream "SitePlugin"). */
@Serializable
data class SitePlugin(
    @SerialName("url") val url: String,
    @SerialName("status") val status: Int = 1, // 0 down, 1 ok, 2 slow, 3 beta only
    @SerialName("version") val version: Int = 1, // bump triggers auto-update
    @SerialName("apiVersion") val apiVersion: Int = 1,
    @SerialName("name") val name: String,
    @SerialName("internalName") val internalName: String,
    @SerialName("authors") val authors: List<String> = emptyList(),
    @SerialName("description") val description: String? = null,
    @SerialName("repositoryUrl") val repositoryUrl: String? = null,
    @SerialName("tvTypes") val tvTypes: List<String>? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("iconUrl") val iconUrl: String? = null,
    @SerialName("fileSize") val fileSize: Long? = null,
    @SerialName("fileHash") val fileHash: String? = null,
)

/** A repo added by the user (our DB row, mirrors upstream RepositoryData). */
@Serializable
data class RepoEntry(
    val url: String,          // manifest url (normalized)
    val name: String?,
    val description: String?,
    val iconUrl: String?,
    val manifestVersion: Int = 1,
    val enabled: Boolean = true,
)

/** Entry of the upstream catalog (repos-db.json): either "url" or {url, verified}. */
@Serializable
data class CatalogEntry(
    @SerialName("url") val url: String,
    @SerialName("verified") val verified: Boolean = false,
)

const val PROVIDER_STATUS_DOWN = 0
const val PROVIDER_STATUS_OK = 1
const val PROVIDER_STATUS_SLOW = 2
const val PROVIDER_STATUS_BETA = 3
