package app.pocket.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The bundled asset and the GitHub file share the same format and parser. */
internal class CatalogRepository(
    private val cache: File,
    private val bundled: () -> InputStream,
    private val fetch: () -> ByteArray
) {
    fun load(): List<CatalogModel> =
        runCatching { cache.inputStream().use { ModelCatalog.parse(readBounded(it).decodeToString()) } }
            .getOrElse { bundled().use { ModelCatalog.parse(readBounded(it).decodeToString()) } }

    fun refresh(): List<CatalogModel> {
        val bytes = fetch()
        require(bytes.size <= MAX_BYTES) { "Model catalog is too large." }
        val models = ModelCatalog.parse(bytes.decodeToString())
        // A failed or malformed response must leave the previous list readable.
        val temporary = Files.createTempFile(cache.parentFile?.toPath(), "model-catalog-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return models
    }

    companion object {
        const val ASSET = "model-catalog.json"
        private const val MAX_BYTES = 128 * 1024

        fun endpoint(repository: String): URL {
            require(ReleaseFeed.validRepository(repository)) { "Catalog repository is not configured." }
            return URL("https://raw.githubusercontent.com/$repository/main/app/src/main/assets/$ASSET")
        }

        fun fetch(repository: String): ByteArray {
            val connection = endpoint(repository).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-cache")
                val code = connection.responseCode
                if (code != 200) throw IOException("Catalog request failed (HTTP $code).")
                return connection.inputStream.use(::readBounded)
            } finally {
                connection.disconnect()
            }
        }

        private fun readBounded(input: InputStream): ByteArray {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return result.toByteArray()
                require(result.size() + count <= MAX_BYTES) { "Model catalog is too large." }
                result.write(buffer, 0, count)
            }
        }
    }
}
