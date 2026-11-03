package app.pocket.local

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelFileDownloaderTest {
    private val content = "A deterministic small test download".toByteArray()
    private fun digest(bytes: ByteArray, type: String = "SHA-256") = MessageDigest.getInstance(type).digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun artifact() = RemoteModelFile("person/model", "a".repeat(40), "weights.gguf", content.size.toLong(), digest(content), "sha256", ModelRole.CHECKPOINT)
    private class Response(private val code: Int, private val bytes: ByteArray, private val headers: Map<String, String> = emptyMap()) : HttpURLConnection(URL("https://huggingface.co")) {
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getHeaderField(name: String?) = headers[name]
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }
    private fun temporary(block: (File) -> Unit) {
        val dir = kotlin.io.path.createTempDirectory("runlocal-download").toFile()
        try { block(File(dir, "weights.part")) } finally { dir.deleteRecursively() }
    }
    @Test fun fullDownloadIsVerified() = temporary { file ->
        ModelFileDownloader { Response(200, content) }.download(artifact(), file)
        assertArrayEquals(content, file.readBytes())
    }
    @Test fun resumeRequestsAndAppendsTheExactRemainingRange() = temporary { file ->
        file.writeBytes(content.take(7).toByteArray())
        val response = Response(206, content.drop(7).toByteArray(), mapOf("Content-Range" to "bytes 7-${content.size - 1}/${content.size}"))
        ModelFileDownloader { response }.download(artifact(), file)
        assertEquals("bytes=7-", response.getRequestProperty("Range"))
        assertArrayEquals(content, file.readBytes())
    }
    @Test fun serverIgnoringRangeRestartsInsteadOfDuplicatingBytes() = temporary { file ->
        file.writeBytes(content.take(7).toByteArray())
        ModelFileDownloader { Response(200, content) }.download(artifact(), file)
        assertArrayEquals(content, file.readBytes())
    }
    @Test fun aWrongRangeNeverGetsAppended() = temporary { file ->
        val prefix = content.take(7).toByteArray(); file.writeBytes(prefix)
        assertThrows(IllegalArgumentException::class.java) {
            ModelFileDownloader { Response(206, content, mapOf("Content-Range" to "bytes 0-${content.size - 1}/${content.size}")) }.download(artifact(), file)
        }
        assertArrayEquals(prefix, file.readBytes())
    }
    @Test fun hashMismatchDeletesTheCorruptCopy() = temporary { file ->
        assertThrows(IllegalStateException::class.java) { ModelFileDownloader { Response(200, ByteArray(content.size)) }.download(artifact(), file) }
        assertFalse(file.exists())
    }
    @Test fun interruptedDownloadKeepsPartialBytesForResume() = temporary { file ->
        assertThrows(IllegalArgumentException::class.java) { ModelFileDownloader { Response(200, content.take(7).toByteArray()) }.download(artifact(), file) }
        assertEquals(7L, file.length())
    }
    @Test fun completePartialIsVerifiedWithoutAnotherRequest() = temporary { file ->
        file.writeBytes(content)
        ModelFileDownloader { error("The complete file should not be requested again") }.download(artifact(), file)
        assertArrayEquals(content, file.readBytes())
    }
    @Test fun unsafeRedirectIsRejected() = temporary { file ->
        var requests = 0
        assertThrows(IllegalArgumentException::class.java) {
            ModelFileDownloader { requests++; Response(302, byteArrayOf(), mapOf("Location" to "http://evil.test/file")) }.download(artifact(), file)
        }
        assertEquals(1, requests)
    }
    @Test fun ordinaryGitFileUsesTheGitBlobHash() = temporary { file ->
        val git = artifact().copy(path = "tokenizer.json", role = ModelRole.TOKENIZER,
            digest = digest("blob ${content.size}\u0000".toByteArray() + content, "SHA-1"), hashType = "git-sha1")
        ModelFileDownloader { Response(200, content) }.download(git, file)
        assertArrayEquals(content, file.readBytes())
    }
}
