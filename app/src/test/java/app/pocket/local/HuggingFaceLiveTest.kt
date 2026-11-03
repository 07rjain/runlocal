package app.pocket.local

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class HuggingFaceLiveTest {
    @Test fun downloadsAndValidatesTheActualAdapterWithoutDownloadingItsBase() {
        assumeTrue(System.getProperty("runlocal.liveModels") == "true")
        val plan = HuggingFaceResolver().resolve("https://huggingface.co/axiomofmind/Moss-image-nsfw-uncensored-lora")
        val adapter = plan.files.single { it.role == ModelRole.LORA }
        val dir = kotlin.io.path.createTempDirectory("runlocal-live-adapter").toFile()
        try {
            val output = ModelFileDownloader().download(adapter, File(dir, "adapter.part"))
            assertEquals(adapter.size, output.length())
            ModelInspector.validate(output, adapter.name, ModelRole.LORA)
        } finally { dir.deleteRecursively() }
    }
    @Test fun resolvesTheUsersLinkAndDownloadsOneSmallVerifiedFile() {
        assumeTrue(System.getProperty("runlocal.liveModels") == "true")
        val plan = HuggingFaceResolver().resolve("https://huggingface.co/axiomofmind/Moss-image-nsfw-uncensored-lora")
        assertEquals(5, plan.files.size); assertEquals("moss", plan.family)
        val metadata = HuggingFaceMetadata().model(plan.repo, "main")
        val file = metadata.getAsJsonArray("siblings").single { it.asJsonObject.get("rfilename").asString == "adapter_config.json" }.asJsonObject
        val small = RemoteModelFile(plan.repo, metadata.get("sha").asString, "adapter_config.json", file.get("size").asLong,
            file.get("blobId").asString, "git-sha1", ModelRole.TOKENIZER)
        val dir = kotlin.io.path.createTempDirectory("runlocal-live").toFile()
        try {
            val output = ModelFileDownloader().download(small, File(dir, "config.part"))
            assertEquals(small.size, output.length())
            assertTrue(output.readText().contains("base_model_name_or_path"))
            val full = output.readBytes()
            output.writeBytes(full.take(17).toByteArray())
            ModelFileDownloader().download(small, output)
            assertArrayEquals(full, output.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
