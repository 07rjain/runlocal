package app.pocket.local

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PhoneModelScannerTest {
    private fun dir(id: String, name: String = id) = PhoneDocument(id, "content://test/$id", name, true)
    private fun file(id: String, name: String = id, size: Long = 32) = PhoneDocument(id, "content://test/$id", name, false, size, 123)
    private class Documents(val folders: Map<String, List<PhoneDocument>>, val headers: Map<String, ByteArray>, val denied: Set<String> = emptySet()) : PhoneDocuments {
        val reads = mutableListOf<Pair<String, Int>>()
        override fun children(directory: PhoneDocument): List<PhoneDocument> {
            if (directory.key in denied) error("Permission denied")
            return folders[directory.key].orEmpty()
        }
        override fun prefix(file: PhoneDocument, maximum: Int): ByteArray {
            reads.add(file.key to maximum)
            if (file.key in denied) error("Permission denied")
            return (headers[file.key] ?: byteArrayOf()).take(maximum).toByteArray()
        }
    }
    private fun tensorHeader(vararg keys: String): ByteArray {
        val json = keys.joinToString(",", "{", "}") { "\"$it\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}" }.toByteArray()
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(json.size.toLong()).array() + json
    }
    @Test fun findsPortableModelsRecursivelyAndIgnoresUnrelatedFiles() {
        val root = dir("root"); val child = dir("child")
        val docs = Documents(mapOf("root" to listOf(child, file("photo", "photo.jpg")), "child" to listOf(file("model", "chat.litertlm"))), mapOf("model" to "LITERTLM".toByteArray()))
        val scan = PhoneModelScanner(docs).scan(root)
        assertEquals(1, scan.models.size); assertEquals(PhoneModelKind.LITERT, scan.models.single().kind)
        assertEquals("root/child", scan.models.single().location)
        assertFalse(docs.reads.any { it.first == "photo" })
    }
    @Test fun cactusBundleGroupsItsComponentsInsteadOfShowingHundredsOfWeightFiles() {
        val root = dir("model", "qwen-cq4"); val components = dir("components")
        val docs = Documents(mapOf("model" to listOf(file("config", "config.txt", 10), components, file("weights", "weights.bin", 100)),
            "components" to listOf(file("manifest", "manifest.json", 20), file("graph", "model.bin", 300))), mapOf("weights" to "CACT".toByteArray()))
        val scan = PhoneModelScanner(docs).scan(root)
        assertEquals(1, scan.models.size); assertEquals(PhoneModelKind.CACTUS_BUNDLE, scan.models.single().kind)
        assertEquals(430L, scan.models.single().size)
        assertFalse(scan.models.single().kind.canImport)
    }
    @Test fun legacyCactusFolderNeedsTheCactusWeightSignature() {
        val root = dir("root")
        val entries = listOf(file("config", "config.json"), file("weights", "weights.bin"))
        val scan = PhoneModelScanner(Documents(mapOf("root" to entries), mapOf("weights" to "CACTweights".toByteArray()))).scan(root)
        assertEquals(PhoneModelKind.CACTUS_BUNDLE, scan.models.single().kind)
        val other = PhoneModelScanner(Documents(mapOf("root" to entries), mapOf("weights" to "unrelated".toByteArray()))).scan(root)
        assertTrue(other.models.isEmpty())
    }
    @Test fun standaloneCactusWeightsAreVisibleButNotClaimedRunnable() {
        val scan = PhoneModelScanner(Documents(emptyMap(), mapOf("model" to "CACTweights".toByteArray()))).scan(file("model", "qwen.cact"))
        assertEquals(PhoneModelKind.CACTUS_FILE, scan.models.single().kind); assertFalse(scan.models.single().kind.canImport)
    }
    @Test fun ordinaryGgufDoesNotBecomeARunnableImageModelJustFromItsExtension() {
        val scan = PhoneModelScanner(Documents(emptyMap(), mapOf("gguf" to "GGUFweights".toByteArray()))).scan(file("gguf", "qwen.gguf"))
        assertEquals(PhoneModelKind.GGUF, scan.models.single().kind); assertFalse(scan.models.single().kind.canImport)
    }
    @Test fun renamedHtmlIsNotAcceptedAsGgufOrLiteRt() {
        assertNull(PhoneModelFormats.detect("model.gguf", "<html>login</html>".toByteArray()))
        assertNull(PhoneModelFormats.detect("model.litertlm", "<html>login</html>".toByteArray()))
    }
    @Test fun mossLoraHeaderIsRecognizedWithoutReadingTheFullWeights() {
        val result = PhoneModelFormats.detect("adapter.safetensors", tensorHeader("diffusion_model.layers.0.proj.ffn_down.lora_A.weight"))!!
        assertEquals(PhoneModelKind.IMAGE_LORA, result.first); assertEquals("moss", result.second)
    }
    @Test fun languageLoraIsNotOfferedToTheImageEngine() {
        val result = PhoneModelFormats.detect("adapter.safetensors", tensorHeader("base_model.model.layers.0.self_attn.q_proj.lora_A.weight"))!!
        assertEquals(PhoneModelKind.LANGUAGE_LORA, result.first); assertFalse(result.first.canImport)
        val plain = PhoneModelFormats.detect("adapter.safetensors", tensorHeader("transformer.layers.0.self_attn.q_proj.lora_A.weight"))!!
        assertEquals(PhoneModelKind.LANGUAGE_LORA, plain.first)
    }
    @Test fun combinedImageCheckpointIsIdentifiedAsACandidate() {
        val result = PhoneModelFormats.detect("sd15.safetensors", tensorHeader("model.diffusion_model.input_blocks.weight", "first_stage_model.decoder.weight", "cond_stage_model.transformer.weight"))!!
        assertEquals(PhoneModelKind.IMAGE_CHECKPOINT, result.first); assertEquals("sd15", result.second)
    }
    @Test fun incompleteLargeHeaderStaysUnverifiedAndMaliciousLengthIsRejected() {
        val large = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(200000).array()
        assertEquals(PhoneModelKind.SAFETENSORS, PhoneModelFormats.detect("model.safetensors", large)!!.first)
        assertFalse(PhoneModelFormats.detect("model.safetensors", large)!!.first.canImport)
        val malicious = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(Long.MAX_VALUE).array()
        assertNull(PhoneModelFormats.detect("model.safetensors", malicious))
    }
    @Test fun partialAndHiddenDownloadsAreNotShown() {
        assertFalse(PhoneModelFormats.candidate("weights.gguf.part"))
        assertFalse(PhoneModelFormats.candidate(".weights.gguf"))
    }
    @Test fun cyclesAndRepeatedFilesAreDeduplicated() {
        val root = dir("root"); val child = dir("child"); val model = file("model", "text.litertlm")
        val docs = Documents(mapOf("root" to listOf(child, model), "child" to listOf(root, model)), mapOf("model" to "LITERTLM".toByteArray()))
        assertEquals(1, PhoneModelScanner(docs).scan(root).models.size)
        assertEquals(1, docs.reads.count { it.first == "model" })
    }
    @Test fun inaccessibleSubfolderDoesNotHideOtherModels() {
        val root = dir("root"); val docs = Documents(mapOf("root" to listOf(dir("denied"), file("model", "text.litertlm"))),
            mapOf("model" to "LITERTLM".toByteArray()), setOf("denied"))
        val scan = PhoneModelScanner(docs).scan(root)
        assertEquals(1, scan.models.size); assertTrue(scan.warnings.isNotEmpty())
    }
    @Test fun itemDepthAndHeaderLimitsAreReported() {
        val root = dir("root"); val child = dir("child")
        val docs = Documents(mapOf("root" to listOf(child, file("a", "a.litertlm"), file("b", "b.litertlm")), "child" to listOf(dir("deeper"))),
            mapOf("a" to "LITERTLM".toByteArray(), "b" to "LITERTLM".toByteArray()))
        assertTrue(PhoneModelScanner(docs, maxItems = 2).scan(root).limited)
        assertTrue(PhoneModelScanner(docs, maxDepth = 0).scan(root).limited)
        assertTrue(PhoneModelScanner(docs, maxHeaderBytes = 4).scan(root).limited)
    }
    @Test fun headerReadsStayBoundedAndZeroByteDownloadsAreIgnored() {
        val root = dir("root")
        val docs = Documents(mapOf("root" to listOf(file("big", "big.gguf", 20_000_000_000L), file("empty", "empty.gguf", 0))), mapOf("big" to "GGUFweights".toByteArray()))
        val scan = PhoneModelScanner(docs).scan(root)
        assertEquals(1, scan.models.size); assertTrue(docs.reads.all { it.second <= 128 * 1024 }); assertFalse(docs.reads.any { it.first == "empty" })
    }
    @Test fun quantizedCactusArchivesAreCandidatesButOrdinaryZipFilesAreIgnored() {
        assertEquals(PhoneModelKind.CACTUS_ARCHIVE, PhoneModelFormats.detect("lfm2-cq4.zip", "PKarchive".toByteArray())!!.first)
        assertNull(PhoneModelFormats.detect("photos.zip", "PKarchive".toByteArray()))
    }
    @Test fun savedCatalogueRoundTripKeepsLocationAndFormat() {
        val source = PhoneModelSource("content://test/tree/models", true, "Models")
        val found = FoundPhoneModel("key", "content://test/file/model", "qwen.cact", PhoneModelKind.CACTUS_FILE, 500, "Models", modified = 123)
        val index = PhoneLibraryIndex(listOf(IndexedModelSource(source, PhoneModelScan(listOf(found), emptyList(), false), 456)))
        assertEquals(index, Gson().fromJson(Gson().toJson(index), PhoneLibraryIndex::class.java))
    }
}
