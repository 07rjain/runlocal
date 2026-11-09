package app.pocket.local

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

interface PinnedDownload {
    val url: String
    val size: Long
    val digest: String
    val hashType: String
    fun validate()
}

/** Byte-range resume and publisher-provided hashes; partially downloaded weights are never selected. */
class ModelFileDownloader(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun download(file: PinnedDownload, destination: File, checkSpace: (Long) -> Unit = {}, progress: (Long, Boolean) -> Unit = { _, _ -> }): File {
        file.validate()
        destination.parentFile?.mkdirs()
        if (destination.length() > file.size) destination.delete()
        var offset = destination.length()
        if (offset < file.size) {
            checkSpace(file.size - offset)
            var url = URL(file.url)
            var connection: HttpURLConnection? = null
            try {
                for (redirect in 0..8) {
                    require(url.protocol == "https" && (url.host == "huggingface.co" || url.host.endsWith(".huggingface.co") || url.host == "hf.co" || url.host.endsWith(".hf.co"))) { "Hugging Face redirected to an unsupported download host." }
                    val current = open(url)
                    connection = current
                    current.instanceFollowRedirects = false
                    current.connectTimeout = 20000; current.readTimeout = 60000
                    current.setRequestProperty("Accept-Encoding", "identity")
                    current.setRequestProperty("User-Agent", "RunLocal/0.3")
                    if (offset > 0) current.setRequestProperty("Range", "bytes=$offset-")
                    val code = current.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        require(redirect < 8) { "Too many download redirects." }
                        url = URL(url, current.getHeaderField("Location") ?: error("Download redirect was missing its destination."))
                        current.disconnect(); continue
                    }
                    require(code == 200 || code == 206) { when (code) {
                        401, 403 -> "This file requires Hugging Face access. Choose a publicly downloadable model."
                        else -> "Download failed (HTTP $code). Tap Resume to try again."
                    } }
                    if (code == 200) offset = 0 // Server ignored Range: replace, never append duplicate bytes.
                    else {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(current.getHeaderField("Content-Range").orEmpty())
                            ?: error("Invalid resumable-download response.")
                        require(range.groupValues[1].toLong() == offset && range.groupValues[3].toLong() == file.size && range.groupValues[2].toLong() == file.size - 1) { "The resumed download does not match the selected file." }
                    }
                    current.inputStream.use { input ->
                        FileOutputStream(destination, offset > 0).use { output ->
                            var copied = offset
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(copied + count <= file.size) { "The server returned an unexpected file size." }
                                checkSpace(count.toLong())
                                output.write(buffer, 0, count); copied += count
                                progress(copied, false)
                            }
                            output.fd.sync()
                        }
                    }
                    break
                }
            } finally { connection?.disconnect() }
        }
        require(destination.length() == file.size) { "The download was interrupted. Tap Resume to continue." }
        val hash = MessageDigest.getInstance(if (file.hashType == "sha256") "SHA-256" else "SHA-1")
        if (file.hashType == "git-sha1") hash.update("blob ${file.size}\u0000".toByteArray())
        destination.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024); var verified = 0L
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count); verified += count; progress(verified, true) }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (actual != file.digest) { destination.delete(); error("The download did not pass its integrity check. Tap Resume to download a fresh copy.") }
        return destination
    }
}
