package app.pocket.local

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.Strictness
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil

internal const val MIB = 1024L * 1024
internal const val MODEL_STORAGE_RESERVE = 256L * MIB

internal enum class ModelTask(val title: String, val description: String, val icon: String, val useAction: String) {
    CHAT("Chat", "Write, summarize and ask questions", "chat", "Start chatting"),
    OCR("Read text from an image", "Copy printed text from a photo", "scan", "Scan an image"),
    SPEAK("Speak text", "Turn written text into a voice", "speaker", "Choose a voice"),
    DICTATE("Transcribe speech", "Dictate into a chat draft", "voice", "Try dictation"),
    IMAGE("Generate images", "Create an image from a prompt", "image", "Create an image")
}

internal sealed interface CatalogSource {
    data class Download(val plan: ModelSetupPlan) : CatalogSource
    data class Speech(val spec: SpeechModelSpec) : CatalogSource
    data object Mirage : CatalogSource
    data object Dictation : CatalogSource
}

internal data class CatalogModel(
    val id: String,
    val task: ModelTask,
    val title: String,
    val summary: String,
    val processor: String,
    val license: String,
    val modelUrl: String?,
    val downloadBytes: Long,
    val memoryBytes: Long?,
    val storageReserve: Long,
    val experimental: Boolean,
    val source: CatalogSource
) {
    // Headlines can change independently of the pinned file or installed copy.
    val recipeId get() = (source as? CatalogSource.Speech)?.spec?.id
    val downloadKey get() = when (source) {
        is CatalogSource.Download -> source.plan.files.single().key
        is CatalogSource.Speech -> source.spec.id
        else -> null
    }
}

internal object ModelCatalog {
    const val dictationId = "android-dictation"
    private val idPattern = Regex("[a-z0-9][a-z0-9.-]{0,63}")
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private val mirage = CatalogModel("mirage", ModelTask.OCR, MirageModel.title,
        "Read printed Latin-script text. Check the result when text is small or stylized.", "CPU · ARM64",
        "Apache 2.0", MirageModel.modelUrl, MirageModel.size, MirageModel.memoryMinimum,
        MirageModel.storageReserve, true, CatalogSource.Mirage)
    private val dictation = CatalogModel(dictationId, ModelTask.DICTATE, "Android offline dictation",
        "Speak into the microphone to fill the chat composer. Uses your phone’s offline language pack.",
        "Android on-device speech service", "Provided by your phone’s speech service", null, 0, null,
        0, false, CatalogSource.Dictation)
    private val builtins = (listOf(mirage, dictation) + SpeechModelCatalog.models.map { spec ->
        CatalogModel(spec.id, ModelTask.SPEAK, spec.title,
            "${spec.voices.joinToString(" and ") { it.name }} speak English from a single download.", "CPU",
            spec.licenseSummary, spec.modelUrl, spec.downloadBytes, spec.memoryBytes, SPEECH_STORAGE_RESERVE,
            true, CatalogSource.Speech(spec))
    }).associateBy { it.id }

    fun parse(json: String, strictAdapters: Boolean = false): List<CatalogModel> {
        val root = requireNotNull(gson.fromJson(json, JsonObject::class.java)) { "Missing catalog." }
        require(root.integer("schemaVersion") == 1L) { "The catalog uses an unsupported format." }
        val models = root.getAsJsonArray("models")
        require(models != null && models.size() <= 100) { "Invalid catalog model list." }
        val ids = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        return models.mapNotNull { value ->
            val entry = value.asJsonObject
            val id = entry.string("id", 64)
            require(idPattern.matches(id) && ids.add(id)) { "Invalid or duplicate catalog ID: $id." }
            val runtime = entry.string("runtime", 32)
            val adapter = entry.optionalString("adapter", 32)
            val recipe = adapter?.let(RecipeAdapter::forKey)
            val model = if (recipe != null) {
                require(runtime == "builtin" && id !in builtins)
                val title = entry.string("title", 80)
                val spec = recipe.read(id, title, entry.string("license", 120), entry)
                CatalogModel(id, recipe.task, title, entry.string("summary", 240), "CPU", spec.licenseSummary,
                    spec.modelUrl, spec.downloadBytes, spec.memoryBytes, SPEECH_STORAGE_RESERVE,
                    entry.flag("experimental"), CatalogSource.Speech(spec))
            } else if (runtime == "builtin" && adapter !in listOf("llama-gguf-v1", "sdxl-v1")) {
                // A newer app can add a built-in adapter without taking old clients' catalog offline.
                val model = builtins[id]?.takeIf { adapter == null }
                if (model == null) {
                    require(!strictAdapters) { "Unsupported adapter or built-in listing for $id." }
                    return@mapNotNull null
                }
                model.copy(title = entry.optionalString("title", 80) ?: model.title,
                    summary = entry.optionalString("summary", 240) ?: model.summary)
            } else {
                require(id !in builtins) { "The catalog ID $id belongs to a built-in model." }
                require(adapter == null || runtime == "builtin")
                val plan = downloadPlan(entry, adapter ?: runtime)
                val file = plan.files.single()
                require(files.add(file.key)) { "The catalog already lists the file for $id." }
                val text = file.role == ModelRole.TEXT
                val gpu = entry.flag("gpu")
                require(!gpu || plan.family == "text") { "This adapter uses CPU processing." }
                CatalogModel(id, if (text) ModelTask.CHAT else ModelTask.IMAGE, plan.title,
                    entry.string("summary", 240), if (gpu) "CPU or GPU, depending on the phone’s graphics driver" else "CPU",
                    entry.string("license", 120), "https://huggingface.co/${file.repo}/tree/${file.revision}",
                    plan.totalBytes, plan.estimatedRam, MODEL_STORAGE_RESERVE, entry.flag("experimental"),
                    CatalogSource.Download(plan))
            }
            model.takeIf { entry.flag("enabled", default = true) }
        }
    }

    private fun downloadPlan(entry: JsonObject, runtime: String): ModelSetupPlan {
        val family = when (runtime) {
            "litert-lm" -> "text"
            "sd15" -> "sd15"
            "llama-gguf-v1" -> "gguf"
            "sdxl-v1" -> "sdxl"
            else -> error("Unsupported model runtime: $runtime.")
        }
        val repo = entry.string("repo", 193)
        require(ModelLink.parse(repo).repo == repo) { "Use owner/repository for Hugging Face models." }
        val file = RemoteModelFile(repo, entry.string("revision", 40), entry.string("file", 512),
            entry.integer("bytes"), entry.string("sha256", 64), "sha256",
            if (family in listOf("text", "gguf")) ModelRole.TEXT else ModelRole.CHECKPOINT)
        return ModelSetupPlan(repo, entry.string("title", 80), family, "", listOf(file)).also { validatePlan(it) }
    }

    /** Validate the exact plan selected in Models again inside the download process. */
    fun validatePlan(plan: ModelSetupPlan) {
        plan.validate()
        val file = plan.files.single()
        require(plan.family in listOf("text", "sd15", "gguf", "sdxl") && plan.trigger.isEmpty() &&
            plan.repo == file.repo && ModelLink.parse(file.repo).repo == file.repo && file.hashType == "sha256") {
            "Unsupported catalog download."
        }
        if (plan.family == "text") require(isGenericTextModel(file.path)) { "Choose a generic LiteRT-LM export." }
        if (plan.family == "gguf") {
            require(!file.name.contains("mmproj", true) && !Regex("-[0-9]{5}-of-[0-9]{5}").containsMatchIn(file.name)) {
                "Choose one complete GGUF chat model."
            }
        }
    }
}

internal fun JsonObject.string(key: String, max: Int): String {
        val value = get(key)
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) { "Missing or invalid $key." }
        return value.asString.also {
            require(it.isNotBlank() && it.length <= max && it.none(Char::isISOControl)) { "Invalid $key." }
        }
    }

internal fun JsonObject.optionalString(key: String, max: Int) = if (has(key)) string(key, max) else null

internal fun JsonObject.integer(key: String): Long {
        val value = get(key)
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber) { "Missing or invalid $key." }
        return requireNotNull(value.asString.toLongOrNull()) { "Invalid $key." }
    }

private fun JsonObject.flag(key: String, default: Boolean = false): Boolean {
        val value = get(key) ?: return default
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Invalid $key." }
        return value.asBoolean
    }

internal enum class FitLevel {
    GOOD, TIGHT, NEEDS_STORAGE, LOW_RAM, UNAVAILABLE
}

internal data class CatalogDevice(val totalRam: Long, val availableRam: Long, val lowMemory: Boolean, val freeStorage: Long) {
    fun fit(model: CatalogModel, remainingBytes: Long, available: Boolean, installed: Boolean): FitLevel {
        if (!available) return FitLevel.UNAVAILABLE
        val ram = model.memoryBytes ?: return FitLevel.GOOD
        if (model.source is CatalogSource.Download && !model.source.plan.fitsInRam(totalRam)) return FitLevel.LOW_RAM
        if (!installed && freeStorage <= remainingBytes + model.storageReserve) return FitLevel.NEEDS_STORAGE
        return if (lowMemory || ram > availableRam || ram > totalRam / 2) FitLevel.TIGHT else FitLevel.GOOD
    }
}

internal fun catalogSize(bytes: Long): String = if (bytes >= 1_000_000_000) {
    String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
} else if (bytes >= 1_000_000) {
    String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
} else {
    "${ceil(bytes / 1000.0).toLong()} KB"
}

/** Measure only new network bytes from this attempt, even if the file already has a partial copy. */
internal class DownloadEta {
    private var startBytes = -1L
    private var startAt = 0L
    private var previousBytes = -1L

    fun update(bytes: Long, total: Long, now: Long): Long? {
        if (startBytes < 0 || bytes < previousBytes) {
            startBytes = bytes
            startAt = now
        }
        previousBytes = bytes
        val elapsed = now - startAt
        val downloaded = bytes - startBytes
        if (elapsed < 1_000 || downloaded <= 0 || bytes >= total) return null
        return ceil((total - bytes).toDouble() / downloaded * elapsed / 1000).toLong()
    }
}

internal fun etaLabel(seconds: Long?): String? = seconds?.let {
    when {
        it < 60 -> "About $it s left"
        it < 3600 -> "About ${(it + 59) / 60} min left"
        else -> "About ${ceil(it / 3600.0).toLong()} hr left"
    }
}

internal fun modelDownloadProgress(setup: JSONObject): String {
    val total = setup.optLong("total")
    val bytes = setup.optLong("bytes")
    return listOfNotNull(setup.optString("message"),
        if (total > 0) "${catalogSize(bytes)} of ${catalogSize(total)}" else null,
        etaLabel(setup.optLong("etaSeconds", -1).takeIf { it >= 0 && setup.optString("state") == "downloading" })
    ).joinToString("\n")
}
