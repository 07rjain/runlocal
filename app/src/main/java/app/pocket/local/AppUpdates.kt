package app.pocket.local

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(val versionCode: Int, val versionName: String, val releaseUrl: String, val prerelease: Boolean = false)

/** Read-only public release metadata. No device identifiers or application content are sent. */
object ReleaseFeed {
    fun validRepository(value: String) = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9][A-Za-z0-9._-]{0,99}").matches(value)
    fun parse(json: String, repository: String, installedCode: Int, includePrereleases: Boolean = false): AppUpdate? {
        require(validRepository(repository)) { "Release repository is not configured." }
        val release = JsonParser.parseString(json).asJsonObject
        if (release.get("draft")?.asBoolean != false) return null
        val prerelease = release.get("prerelease")?.takeUnless { it.isJsonNull }?.asBoolean ?: return null
        if (prerelease && !includePrereleases) return null
        val tag = release.get("tag_name")?.asString ?: return null
        if (!Regex("v?[0-9][A-Za-z0-9.+-]{0,80}").matches(tag)) return null
        val markers = Regex("<!-- runlocal-version-code: ([0-9]+) -->").findAll(release.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty()).toList()
        val code = markers.singleOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (code <= installedCode) return null
        val url = "https://github.com/$repository/releases/tag/$tag"
        if (release.get("html_url")?.asString != url) return null
        val assetName = "runlocal-${tag.removePrefix("v")}-arm64.apk"
        val expectedAsset = "https://github.com/$repository/releases/download/$tag/$assetName"
        val hasApk = release.getAsJsonArray("assets")?.any {
            val asset = it.asJsonObject
            asset.get("name")?.asString == assetName && asset.get("browser_download_url")?.asString == expectedAsset && (asset.get("size")?.asLong ?: 0) > 0
        } == true
        return if (hasApk) AppUpdate(code, tag.removePrefix("v"), url, prerelease) else null
    }
    fun parseFeed(json: String, repository: String, installedCode: Int, includePrereleases: Boolean = false): AppUpdate? {
        require(validRepository(repository)) { "Release repository is not configured." }
        // Each entry is untrusted public JSON. An invalid entry must not hide a
        // separately valid release; all accepted entries still pass the same checks.
        return JsonParser.parseString(json).asJsonArray.mapNotNull { entry ->
            runCatching { parse(entry.toString(), repository, installedCode, includePrereleases) }.getOrNull()
        }.maxByOrNull { it.versionCode }
    }
    fun fetch(repository: String, installedCode: Int, includePrereleases: Boolean = false): AppUpdate? {
        require(validRepository(repository))
        val path = if (includePrereleases) "releases?per_page=30" else "releases/latest"
        val connection = URL("https://api.github.com/repos/$repository/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000; connection.readTimeout = 10000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "RunLocal/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode == 404) return null
            check(connection.responseCode == 200) { "Could not check GitHub releases (HTTP ${connection.responseCode})." }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    check(output.size() + count <= 1024 * 1024) { "Release metadata is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val json = bytes.toString(Charsets.UTF_8)
            return if (includePrereleases) parseFeed(json, repository, installedCode, true) else parse(json, repository, installedCode)
        } finally { connection.disconnect() }
    }
}
