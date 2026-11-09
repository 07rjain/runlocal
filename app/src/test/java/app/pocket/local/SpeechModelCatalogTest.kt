package app.pocket.local

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class SpeechModelCatalogTest {
    @Test fun supportedModelLinksSelectTheSameRecipe() {
        val spec = SpeechModelCatalog.models.single()
        assertEquals(spec, SpeechModelCatalog.forLink(spec.modelUrl))
        assertEquals(spec, SpeechModelCatalog.forLink(spec.files.single().url))
        assertEquals(setOf("juno", "mira"), spec.voices.map { it.id }.toSet())
        spec.files.forEach { it.validate() }
    }
    @Test fun arbitraryWeightsAndDifferentRevisionsAreNotExecutableModels() {
        assertNull(SpeechModelCatalog.forLink("https://huggingface.co/other/speech"))
        assertNull(SpeechModelCatalog.forLink("https://huggingface.co/ConwayResearch/Underdog-Woof/blob/main/another.npz"))
        assertNull(SpeechModelCatalog.forLink("https://huggingface.co/ConwayResearch/Underdog-Woof/tree/unreviewed"))
        assertThrows(IllegalStateException::class.java) { SpeechModelCatalog.find("arbitrary-file") }
    }
    @Test fun speechDownloadsRejectUnpinnedOrUnsafeFiles() {
        val file = SpeechModelCatalog.models.single().files.single()
        listOf(file.copy(revision = "main"), file.copy(path = "../weights.npz"), file.copy(digest = ""), file.copy(size = 0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
    }
    @Test fun speechWeightsUseTheSharedDownloaderAndRejectCorruption() {
        val content = "small speech weights".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
        val file = SpeechModelCatalog.models.single().files.single().copy(size = content.size.toLong(), digest = digest)
        val directory = kotlin.io.path.createTempDirectory("speech-download").toFile()
        val destination = File(directory, "weights.part")
        fun downloader(bytes: ByteArray) = ModelFileDownloader { object : HttpURLConnection(URL(file.url)) {
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream(bytes)
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
        } }
        try {
            downloader(content).download(file, destination)
            assertArrayEquals(content, destination.readBytes())
            destination.delete()
            assertThrows(IllegalStateException::class.java) { downloader(ByteArray(content.size)).download(file, destination) }
            assertFalse(destination.exists())
        } finally { directory.deleteRecursively() }
    }
}
