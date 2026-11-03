package app.pocket.local

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class HuggingFaceResolverTest {
    private fun fixture(repo: String): JsonObject = (javaClass.getResourceAsStream("/huggingface/${repo.replace("/", "--")}.json")
        ?: error("Missing metadata fixture: $repo")).bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    private fun resolver() = HuggingFaceResolver(ModelMetadataSource { repo, _ -> fixture(repo) })

    @Test fun acceptsRepositoryAndDirectFileLinks() {
        assertEquals("person/model", ModelLink.parse("https://huggingface.co/person/model/").repo)
        assertEquals("person/model", ModelLink.parse("person/model").repo)
        val link = ModelLink.parse("https://huggingface.co/person/model/blob/main/a/model.safetensors?download=true")
        assertEquals("a/model.safetensors", link.file)
        assertEquals("main", link.revision)
    }
    @Test fun refusesOtherHostsCredentialsAndTraversal() {
        listOf("https://huggingface.co.evil.test/person/model", "https://huggingface.co@evil.test/person/model",
            "https://someone@huggingface.co/person/model", "https://huggingface.co/person/model/resolve/main/../secret",
            "https://huggingface.co/spaces/person/model", "http://huggingface.co/person/model").forEach { link ->
            assertThrows("Unsafe link accepted: $link", Exception::class.java) { ModelLink.parse(link) }
        }
    }
    @Test fun linkedMossLoraResolvesAllDependenciesWithoutUserFileChoices() {
        val plan = resolver().resolve("https://huggingface.co/axiomofmind/Moss-image-nsfw-uncensored-lora")
        assertEquals("moss", plan.family); assertEquals(5, plan.files.size)
        assertEquals("moss_xvstyle", plan.trigger)
        assertEquals(ModelRole.entries.filter { it !in listOf(ModelRole.CHECKPOINT, ModelRole.TEXT) }.toSet(), plan.files.map { it.role }.toSet())
        assertTrue(plan.files.single { it.role == ModelRole.LORA }.path.contains("LoRA-ComfyUI"))
        assertTrue(plan.files.all { it.revision != "main" && it.digest.isNotEmpty() && it.size > 0 })
        assertTrue(plan.totalBytes > 8_000_000_000L)
    }
    @Test fun directAdapterLinkSelectsThatFileInsteadOfGuessing() {
        val plan = resolver().resolve("https://huggingface.co/axiomofmind/Moss-image-nsfw-uncensored-lora/blob/main/pytorch_lora_weights.safetensors")
        assertEquals("pytorch_lora_weights.safetensors", plan.files.single { it.role == ModelRole.LORA }.path)
    }
    @Test fun baseMossDoesNotGetAnInventedAdapter() {
        val plan = resolver().resolve("OpenMOSS-Team/MOSS-Image-SFT")
        assertEquals(4, plan.files.size); assertFalse(plan.files.any { it.role == ModelRole.LORA }); assertEquals("", plan.trigger)
    }
    @Test fun lightweightStarterNeedsOnlyOneDownload() {
        val plan = resolver().resolve("https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF")
        assertEquals("sd15", plan.family); assertEquals(1, plan.files.size); assertEquals(ModelRole.CHECKPOINT, plan.files.single().role)
    }
    @Test fun planRoundTripPreservesPinnedFilesAndHashes() {
        val plan = resolver().resolve("axiomofmind/Moss-image-nsfw-uncensored-lora")
        assertEquals(plan, ModelSetupPlan.fromJson(plan.toJson()))
    }
    @Test fun unsupportedModelsDoNotSilentlySwitchToADifferentModel() {
        val resolver = HuggingFaceResolver(ModelMetadataSource { _, _ -> JsonParser.parseString("""{"sha":"${"a".repeat(40)}","siblings":[],"cardData":{"base_model":"somewhere/video-model"}}""").asJsonObject })
        val error = assertThrows(IllegalStateException::class.java) { resolver.resolve("person/video-lora") }
        assertTrue(error.message.orEmpty().contains("not supported"))
    }
    @Test fun aMissingDependencyStopsResolutionBeforeAnyDownloads() {
        val resolver = HuggingFaceResolver(ModelMetadataSource { repo, _ -> fixture(repo).also { if (repo == "Comfy-Org/MOSS-Image") it.getAsJsonArray("siblings").remove(0) } })
        val error = assertThrows(IllegalStateException::class.java) { resolver.resolve("axiomofmind/Moss-image-nsfw-uncensored-lora") }
        assertTrue(error.message.orEmpty().contains("required download"))
    }
    @Test fun triggerIsAddedAutomaticallyWithoutDuplicatingIt() {
        assertEquals("moss_xvstyle, a ceramic cup", withModelTrigger("a ceramic cup", "moss_xvstyle"))
        assertEquals("MOSS_XVSTYLE, a ceramic cup", withModelTrigger("MOSS_XVSTYLE, a ceramic cup", "moss_xvstyle"))
        assertEquals("a ceramic cup", withModelTrigger("a ceramic cup", ""))
    }
    private fun textResolver(pipeline: String = "text-generation") = HuggingFaceResolver(ModelMetadataSource { _, _ ->
        JsonParser.parseString("""{"sha":"${"a".repeat(40)}","pipeline_tag":"$pipeline","siblings":[
        {"rfilename":"small.litertlm","lfs":{"size":1000,"sha256":"${"b".repeat(64)}"}},
        {"rfilename":"large.litertlm","lfs":{"size":2000,"sha256":"${"c".repeat(64)}"}},
        {"rfilename":"mediatek/npu.litertlm","lfs":{"size":500,"sha256":"${"d".repeat(64)}"}}]}""").asJsonObject
    })
    @Test fun choosesSmallestGenericTextModelAndPinsItsHash() {
        val plan = textResolver().resolve("person/chat-model")
        assertEquals("text", plan.family)
        assertEquals("small.litertlm", plan.files.single().path)
        assertEquals(ModelRole.TEXT, plan.files.single().role)
        assertEquals(plan, ModelSetupPlan.fromJson(plan.toJson()))
    }
    @Test fun explicitTextFileIsRespected() {
        val plan = textResolver().resolve("https://huggingface.co/person/chat-model/blob/main/large.litertlm")
        assertEquals("large.litertlm", plan.files.single().path)
    }
    @Test fun rejectsDeviceSpecificTextFileAndNonChatPipelines() {
        assertThrows(Exception::class.java) { textResolver().resolve("https://huggingface.co/person/chat-model/blob/main/mediatek/npu.litertlm") }
        assertThrows(Exception::class.java) { textResolver("automatic-speech-recognition").resolve("person/speech") }
    }

    @Test fun exactReportedLfm3LinkResolvesTheRequestedExportAndPublisherHash() {
        val plan = resolver().resolve("https://huggingface.co/litert-community/LFM3-1.7B-LiteRT/blob/main/LFM3-1.7B_mixed_int4_ekv2048.litertlm")
        assertEquals("text", plan.family)
        val file = plan.files.single()
        assertEquals("LFM3-1.7B_mixed_int4_ekv2048.litertlm", file.path)
        assertEquals(1_441_620_928L, file.size)
        assertEquals("0400d8ffdb00e4bbe1209120e2c7baf05322a8c900839b0a94cc28f599011196", file.digest)
        assertEquals("sha256", file.hashType)
    }

}
