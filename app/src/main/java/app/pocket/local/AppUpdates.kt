package app.pocket.local

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

data class UpdateApk(val url: String, val bytes: Long, val sha256: String)

data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val releaseUrl: String,
    val prerelease: Boolean = false,
    val apk: UpdateApk? = null
)

/** Read-only public release metadata. No device identifiers or application content are sent. */
object ReleaseFeed {
    internal const val MAX_APK_BYTES = 512L * 1024 * 1024
    private val sha256 = Regex("[a-f0-9]{64}")

    fun validRepository(value: String) = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9][A-Za-z0-9._-]{0,99}").matches(value)
    fun parse(json: String, repository: String, installedCode: Int, includePrereleases: Boolean = false): AppUpdate? {
        require(validRepository(repository)) { "Release repository is not configured." }
        val release = JsonParser.parseString(json).asJsonObject
        if (release.get("draft")?.asBoolean != false) return null
        val prerelease = release.get("prerelease")?.takeUnless { it.isJsonNull }?.asBoolean ?: return null
        if (prerelease && !includePrereleases) return null
        val tag = release.get("tag_name")?.asString ?: return null
        if (!Regex("v?[0-9][A-Za-z0-9.+-]{0,80}").matches(tag)) return null
        val body = release.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val markers = Regex("<!-- runlocal-version-code: ([0-9]+) -->").findAll(body).toList()
        val code = markers.singleOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (code <= installedCode) return null
        val url = "https://github.com/$repository/releases/tag/$tag"
        if (release.get("html_url")?.asString != url) return null
        val assetName = "runlocal-${tag.removePrefix("v")}-arm64.apk"
        val expectedAsset = "https://github.com/$repository/releases/download/$tag/$assetName"
        val asset = release.getAsJsonArray("assets")?.filter { it.isJsonObject }?.map { it.asJsonObject }
            ?.singleOrNull { it.get("name")?.asString == assetName && it.get("browser_download_url")?.asString == expectedAsset }
            ?: return null
        val bytes = asset.get("size")?.asLong?.takeIf { it in 1..MAX_APK_BYTES } ?: return null
        val digest = asset.get("digest")?.takeUnless { it.isJsonNull }?.asString
        val notesDigest = Regex("^APK SHA-256: `([a-f0-9]{64})`$", RegexOption.MULTILINE)
            .findAll(body).singleOrNull()?.groupValues?.get(1)
        val apkHash = if (digest == null) notesDigest else {
            digest.removePrefix("sha256:")
                .takeIf { digest.startsWith("sha256:") && sha256.matches(it) && (notesDigest == null || notesDigest == it) }
                ?: return null
        }
        return AppUpdate(code, tag.removePrefix("v"), url, prerelease, apkHash?.let { UpdateApk(expectedAsset, bytes, it) })
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
