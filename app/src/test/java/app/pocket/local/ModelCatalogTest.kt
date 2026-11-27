package app.pocket.local

import java.io.File
import java.io.IOException
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelCatalogTest {
    @get:Rule val temp = TemporaryFolder()
    private val asset = File("src/main/assets", CatalogRepository.ASSET)
    private val fixture = """{
        "schemaVersion": 1,
        "models": [
            {"id":"example-chat","runtime":"litert-lm","title":"Example Chat","summary":"One model",
             "repo":"example/chat","revision":"${"b".repeat(40)}","file":"example.litertlm","bytes":347251840,
             "sha256":"${"c".repeat(64)}","license":"Apache 2.0"},
            {"id":"mirage","runtime":"builtin"},
            {"id":"android-dictation","runtime":"builtin"}
        ]
    }"""
    private val entries = ModelCatalog.parse(fixture)
    private val gib = 1024 * MIB
    private val phone = CatalogDevice(8 * gib, 5 * gib, false, 10 * gib)
    private val chat = entries.first { it.task == ModelTask.CHAT }

    @Test fun pinnedThinkingExportUsesItsOwnTextAdapterAndKeepsTheFastExport() {
        val catalog = ModelCatalog.parse(asset.readText(), strictAdapters = true)
        fun plan(id: String) = (catalog.single { it.id == id }.source as CatalogSource.Download).plan
        val fast = plan("qwen3-0.6b")
        val thinking = plan("qwen3-0.6b-thinking")
        assertNull(fast.textAdapter)
        assertEquals(QwenThinking.adapter, ModelSetupPlan.fromJson(thinking.toJson()).textAdapter)
        assertNotEquals(fast.files.single().key, thinking.files.single().key)
        assertTrue(thinking.isText)
        assertThrows(IllegalArgumentException::class.java) { thinking.copy(textAdapter = "unknown").validate() }
    }

    @Test fun bundledAdminEditsUseExistingRuntimesAndPinnedDownloads() {
        val bundled = ModelCatalog.parse(asset.readText(), strictAdapters = true)
        assertEquals(bundled.size, bundled.map { it.id }.distinct().size)
        for (model in bundled) {
            when (val source = model.source) {
                is CatalogSource.Download -> {
                    val plan = source.plan
                    plan.validate()
                    ModelCatalog.validatePlan(ModelSetupPlan.fromJson(plan.toJson()))
                    assertEquals(plan.totalBytes, model.downloadBytes)
                    assertEquals(plan.estimatedRam, model.memoryBytes)
                    assertTrue(plan.files.all { it.hashType == "sha256" })
                    assertEquals(model.task == ModelTask.CHAT, plan.isText)
                }
                is CatalogSource.Speech -> {
                    assertEquals(source.spec.id, model.downloadKey)
                    source.spec.files.forEach { it.validate() }
                    assertEquals(source.spec.downloadBytes, model.downloadBytes)
                }
                CatalogSource.Mirage -> {
                    MirageModel.validate()
                    assertEquals(MirageModel.size, model.downloadBytes)
                }
                CatalogSource.Dictation -> assertEquals(0L, model.downloadBytes)
            }
        }
    }

    @Test fun deviceFitAccountsForPartialDownloadsAndInstalledModels() {
        val freeStorage = chat.downloadBytes / 2 + chat.storageReserve
        val limited = phone.copy(freeStorage = freeStorage)
        assertEquals(FitLevel.NEEDS_STORAGE, limited.fit(chat, chat.downloadBytes, available = true, installed = false))
        assertEquals(FitLevel.NEEDS_STORAGE, limited.fit(chat, chat.downloadBytes / 2, available = true, installed = false))
        assertEquals(FitLevel.GOOD, limited.fit(chat, chat.downloadBytes / 2 - 1, available = true, installed = false))
        assertEquals(FitLevel.GOOD, phone.copy(freeStorage = 0).fit(chat, 0, available = true, installed = true))
    }

    @Test fun memoryWarningsAndRuntimeAvailabilityHaveDistinctResults() {
        assertEquals(FitLevel.LOW_RAM, phone.copy(totalRam = 1792 * MIB).fit(chat, chat.downloadBytes, true, false))
        assertEquals(FitLevel.TIGHT, phone.copy(availableRam = 128 * MIB).fit(chat, chat.downloadBytes, true, false))
        val ocr = entries.single { it.task == ModelTask.OCR }
        assertEquals(FitLevel.UNAVAILABLE, phone.fit(ocr, ocr.downloadBytes, false, false))
        val dictation = entries.single { it.task == ModelTask.DICTATE }
        assertEquals(FitLevel.GOOD, phone.copy(freeStorage = 0).fit(dictation, 0, true, false))
        assertEquals(FitLevel.UNAVAILABLE, phone.fit(dictation, 0, false, false))
    }

    @Test fun etaUsesOnlyBytesTransferredThisAttemptAndResetsOnRestart() {
        val eta = DownloadEta()
        assertNull(eta.update(500, 1_000, 0))
        assertNull(eta.update(550, 1_000, 500))
        assertEquals(4L, eta.update(600, 1_000, 1_000))
        assertNull(eta.update(20, 1_000, 1_500))
        assertEquals(9L, eta.update(120, 1_000, 2_500))
        assertNull(eta.update(1_000, 1_000, 3_500))
    }

    @Test fun refreshReplacesTheListAndKeepsTheLastGoodCopyOffline() {
        val cache = File(temp.root, "cache.json")
        val changed = JsonParser.parseString(fixture).asJsonObject
        val models = changed.getAsJsonArray("models")
        val added = models[0].asJsonObject.deepCopy()
        added.addProperty("id", "another-model")
        added.addProperty("sha256", "a".repeat(64))
        models.remove(0)
        models.add(added)
        var response = changed.toString()
        val repo = CatalogRepository(cache, { fixture.byteInputStream() }) { response.encodeToByteArray() }
        assertEquals(entries, repo.load())
        val updated = repo.refresh()
        assertFalse(updated.any { it.id == "example-chat" })
        assertTrue(updated.any { it.id == "another-model" })
        assertEquals(updated, repo.load())
        response = """{"schemaVersion":2,"models":[]}"""
        assertThrows(IllegalArgumentException::class.java) { repo.refresh() }
        assertEquals(updated, repo.load())
        response = """{"schemaVersion":1,"models":[],}"""
        assertThrows(JsonSyntaxException::class.java) { repo.refresh() }
        assertEquals(updated, repo.load())
        val offline = CatalogRepository(cache, { fixture.byteInputStream() }) { throw IOException("offline") }
        assertThrows(IOException::class.java) { offline.refresh() }
        assertEquals(updated, offline.load())
        added.addProperty("enabled", false)
        response = changed.toString()
        assertFalse(repo.refresh().any { it.id == "another-model" })
        response = """{"schemaVersion":1,"models":[]}"""
        assertTrue(repo.refresh().isEmpty())
        assertTrue(repo.load().isEmpty())
    }

    @Test fun removedOrReplacedEntriesDoNotInvalidateTheOriginalPinnedDownload() {
        val plan = (chat.source as CatalogSource.Download).plan
        val queued = plan.toJson()
        val emptyCatalog = ModelCatalog.parse("""{"schemaVersion":1,"models":[]}""")
        assertTrue(emptyCatalog.none { it.id == chat.id })
        val restored = ModelSetupPlan.fromJson(queued)
        ModelCatalog.validatePlan(restored)
        assertEquals(plan, restored)
    }

    @Test fun invalidDownloadsAndDuplicateEntriesAreRejected() {
        val root = JsonParser.parseString(fixture).asJsonObject
        val models = root.getAsJsonArray("models")
        val first = models.first().asJsonObject
        val original = first.deepCopy()
        first.addProperty("revision", "main")
        assertThrows(RuntimeException::class.java) { ModelCatalog.parse(root.toString()) }
        first.add("revision", original.get("revision"))
        first.addProperty("file", "qwen3.qcom.npu.litertlm")
        assertThrows(RuntimeException::class.java) { ModelCatalog.parse(root.toString()) }
        first.add("file", original.get("file"))
        first.addProperty("repo", "https://example.org/model")
        assertThrows(RuntimeException::class.java) { ModelCatalog.parse(root.toString()) }
        first.add("repo", original.get("repo"))
        models.add(original)
        assertThrows(RuntimeException::class.java) { ModelCatalog.parse(root.toString()) }
    }

    @Test fun catalogRequestsAreScopedToTheConfiguredGitHubFile() {
        assertEquals("https://raw.githubusercontent.com/07rjain/runlocal/main/app/src/main/assets/model-catalog.json",
            CatalogRepository.endpoint("07rjain/runlocal").toString())
        assertThrows(IllegalArgumentException::class.java) { CatalogRepository.endpoint("07rjain/runlocal/other") }
    }

    @Test fun aFutureBuiltinDoesNotHideEntriesSupportedByThisBuild() {
        val catalog = ModelCatalog.parse("""{"schemaVersion":1,"models":[
            {"id":"future-speech","runtime":"builtin"},
            {"id":"kitten-2-pico-en-int4","runtime":"builtin"}
        ]}""")
        assertEquals(listOf("kitten-2-pico-en-int4"), catalog.map { it.id })
    }

    @Test fun changedRecipeWeightsGetTheirOwnIdentityWhileTheInstalledRecipeStillDecodes() {
        val root = JsonParser.parseString(asset.readText()).asJsonObject
        val entry = root.getAsJsonArray("models").map { it.asJsonObject }
            .single { it.get("id").asString == "kokoro-int8-en" }
        fun recipe() = (ModelCatalog.parse(root.toString(), strictAdapters = true)
            .single { it.id == "kokoro-int8-en" }.source as CatalogSource.Speech).spec
        val original = recipe()
        val installed = RecipeAdapter.encode(original)

        entry.addProperty("title", "Renamed voice")
        assertEquals(original.id, recipe().id)
        val model = entry.getAsJsonObject("files").getAsJsonObject("model")
        model.addProperty("path", "mirror/int8.onnx")
        assertEquals(original.id, recipe().id)
        assertEquals(speechReadySignature(original), speechReadySignature(recipe()))
        model.addProperty("sha256", "2".repeat(64))
        assertNotEquals(original.id, recipe().id)
        assertEquals(original, RecipeAdapter.decode(installed))
    }

    @Test fun strictCatalogRejectsUnregisteredAdaptersAndInvalidRecipeFiles() {
        val root = JsonParser.parseString(asset.readText()).asJsonObject
        val entry = root.getAsJsonArray("models").map { it.asJsonObject }
            .single { it.get("id").asString == "tesseract-english" }
        val original = entry.deepCopy()
        val files = entry.getAsJsonObject("files")
        files.getAsJsonObject("language").addProperty("path", "../eng.traineddata")
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.parse(root.toString(), strictAdapters = true) }
        files.remove("language")
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.parse(root.toString(), strictAdapters = true) }
        entry.add("files", original.get("files"))
        entry.addProperty("adapter", "unshipped-v1")
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.parse(root.toString(), strictAdapters = true) }
        assertFalse(ModelCatalog.parse(root.toString()).any { it.id == "tesseract-english" })
    }
}
