package app.pocket.local

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.io.ByteArrayOutputStream

fun withModelTrigger(prompt: String, trigger: String): String =
    if (trigger.isNotBlank() && !prompt.contains(trigger, ignoreCase = true)) "$trigger, $prompt" else prompt

data class ModelLink(val repo: String, val revision: String = "main", val file: String? = null) {
    companion object {
        fun parse(value: String): ModelLink {
            val raw = value.trim()
            val uri = runCatching { URI(if (raw.startsWith("https://")) raw else "https://huggingface.co/$raw") }
                .getOrElse { error("Paste a valid Hugging Face model link.") }
            require(uri.scheme == "https" && uri.host == "huggingface.co" && uri.userInfo == null && uri.port == -1) { "Paste a huggingface.co model link." }
            val parts = uri.path.trim('/').split('/')
            val name = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
            require(parts.size >= 2 && parts.take(2).all { name.matches(it) && it !in listOf(".", "..") }) { "Use a link to a model repository, such as huggingface.co/owner/model." }
            require(parts[0] !in listOf("spaces", "datasets", "api")) { "Choose a model repository, rather than a Space or dataset." }
            if (parts.size == 2) return ModelLink(parts.take(2).joinToString("/"))
            require(parts.size >= 4 && parts[2] in listOf("blob", "resolve", "tree") && Regex("[A-Za-z0-9._-]{1,100}").matches(parts[3])) { "Use the repository link or a direct model-file link." }
            val file = if (parts[2] == "tree") null else parts.drop(4).joinToString("/").also { checkPath(it) }
            return ModelLink(parts.take(2).joinToString("/"), parts[3], file)
        }
        fun checkPath(path: String) {
            require(path.isNotBlank() && path.length <= 512 && path.split('/').none { it.isBlank() || it == "." || it == ".." } && path.none { it == '\\' || it.code < 32 }) { "Invalid model-file path." }
        }
    }
}

data class RemoteModelFile(val repo: String, val revision: String, val path: String, val size: Long,
                           val digest: String, val hashType: String, val role: ModelRole) {
    val name: String get() = path.substringAfterLast('/')
    val key: String get() = "${role.name}-$hashType-$digest"
    val url: String get() = URI("https", "huggingface.co", "/$repo/resolve/$revision/$path", null).toASCIIString()
    fun validate() {
        ModelLink.parse(repo)
        require(Regex("[0-9a-f]{40}").matches(revision)) { "The model revision could not be pinned." }
        ModelLink.checkPath(path)
        require(size in 1..(64L * 1024 * 1024 * 1024)) { "Invalid download size." }
        require((hashType == "sha256" && Regex("[0-9a-f]{64}").matches(digest)) ||
            (hashType == "git-sha1" && Regex("[0-9a-f]{40}").matches(digest))) { "Download integrity metadata is missing." }
        require(if (role == ModelRole.TEXT) name.endsWith(".litertlm") else if (role == ModelRole.TOKENIZER) name.endsWith(".json") else name.endsWith(".gguf") || name.endsWith(".safetensors")) { "Unsupported download format." }
    }
}

data class ModelSetupPlan(val repo: String, val title: String, val family: String, val trigger: String,
                          val files: List<RemoteModelFile>) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val estimatedRam: Long get() = (totalBytes * 1.25).toLong() + 512L * 1024 * 1024
    fun validate() {
        ModelLink.parse(repo)
        require(family in listOf("sd15", "moss", "text") && files.size in 1..5)
        files.forEach { it.validate() }
        val required = if (family == "text") setOf(ModelRole.TEXT) else if (family == "moss") setOf(ModelRole.DIFFUSION, ModelRole.ENCODER, ModelRole.VAE, ModelRole.TOKENIZER) else setOf(ModelRole.CHECKPOINT)
        require(files.map { it.role }.toSet().containsAll(required) && files.map { it.role }.distinct().size == files.size) { "The automatic setup is incomplete." }
        require(files.all { it.role in required || (family != "text" && it.role == ModelRole.LORA) })
        require(trigger.length <= 128 && trigger.none { it.code < 32 })
    }
    fun toJson(): String = Gson().toJson(this)
    companion object {
        fun fromJson(value: String) = Gson().fromJson(value, ModelSetupPlan::class.java).also { it.validate() }
    }
}

fun interface ModelMetadataSource { fun model(repo: String, revision: String): JsonObject }

class HuggingFaceMetadata : ModelMetadataSource {
    override fun model(repo: String, revision: String): JsonObject {
        ModelLink.parse(repo)
        require(Regex("[A-Za-z0-9._-]{1,100}").matches(revision))
        val url = URI("https", "huggingface.co", "/api/models/$repo/revision/$revision", "blobs=true", null).toURL()
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20000; connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RunLocal/0.3")
            val code = connection.responseCode
            require(code == 200) { when (code) {
                401, 403 -> "This repository requires Hugging Face access. Automatic setup currently supports public downloads."
                404 -> "This model or revision was not found on Hugging Face. Check the link."
                else -> "Hugging Face could not be reached (HTTP $code). Try again shortly."
            } }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(16384)
                while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 8 * 1024 * 1024) { "This repository is too large to inspect automatically." }; output.write(buffer, 0, count) }
                output.toByteArray()
            }
            return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        } finally { connection.disconnect() }
    }
}

/** Known working engine layouts, selected from repository metadata rather than guessed filenames. */
class HuggingFaceResolver(private val source: ModelMetadataSource = HuggingFaceMetadata()) {
    private val cache = mutableMapOf<String, JsonObject>()
    private fun model(repo: String, revision: String = "main") = cache.getOrPut("$repo@$revision") { source.model(repo, revision) }
    private fun file(repo: String, role: ModelRole, path: String, revision: String = "main"): RemoteModelFile {
        val metadata = model(repo, revision)
        val entry = metadata.getAsJsonArray("siblings").firstOrNull { it.asJsonObject.get("rfilename").asString == path }?.asJsonObject
            ?: error("A required download is unavailable. This model cannot be set up automatically right now.")
        val lfs = entry.get("lfs")?.takeIf { it.isJsonObject }?.asJsonObject
        return RemoteModelFile(repo, metadata.get("sha").asString, path,
            lfs?.get("size")?.asLong ?: entry.get("size").asLong,
            lfs?.get("sha256")?.asString ?: entry.get("blobId").asString,
            if (lfs != null) "sha256" else "git-sha1", role).also { it.validate() }
    }
    fun resolve(value: String): ModelSetupPlan {
        cache.clear()
        val link = ModelLink.parse(value)
        val metadata = model(link.repo, link.revision)
        val card = metadata.get("cardData")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val textFiles = metadata.getAsJsonArray("siblings").map { it.asJsonObject.get("rfilename").asString }
            .filter { it.endsWith(".litertlm") }
        if (textFiles.isNotEmpty() || link.file?.endsWith(".litertlm") == true) {
            val pipeline = metadata.get("pipeline_tag")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            require(pipeline.isEmpty() || pipeline in listOf("text-generation", "image-text-to-text")) { "This repository is not a chat text model." }
            val generic = textFiles.filterNot { Regex("(mediatek|qualcomm|npu|hexagon|exynos)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            val candidates = (link.file?.let { selected -> listOf(selected).also { require(selected in generic) { "Choose a generic CPU-compatible .litertlm model." } } } ?: generic)
                .map { file(link.repo, ModelRole.TEXT, it, link.revision) }
            val chosen = candidates.minByOrNull { it.size } ?: error("This repository only provides device-specific models. Choose a CPU-compatible LiteRT-LM model.")
            return ModelSetupPlan(link.repo, chosen.name, "text", "", listOf(chosen)).also { it.validate() }
        }
        val baseValue = card.get("base_model")
        val bases = when {
            baseValue == null || baseValue.isJsonNull -> emptyList()
            baseValue.isJsonArray -> baseValue.asJsonArray.filter { it.isJsonPrimitive }.map { it.asString }
            baseValue.isJsonPrimitive -> listOf(baseValue.asString)
            else -> emptyList()
        }
        val mossBases = setOf("OpenMOSS-Team/MOSS-Image-SFT", "leejet/MOSS-Image-SFT-GGUF")
        val sdBases = setOf("runwayml/stable-diffusion-v1-5", "stable-diffusion-v1-5/stable-diffusion-v1-5")
        val starter = "second-state/stable-diffusion-v1-5-GGUF"
        val family = when {
            link.repo in mossBases || bases.any { it in mossBases } -> "moss"
            link.repo == starter || link.repo in sdBases || bases.any { it in sdBases } -> "sd15"
            else -> error("This model is not supported by the installed runtimes. Use a CPU-compatible .litertlm text model, SD 1.x model, or supported MOSS LoRA.")
        }
        val isBase = link.repo in mossBases || link.repo == starter || link.repo in sdBases
        val files = mutableListOf<RemoteModelFile>()
        if (family == "moss") {
            files += file("leejet/MOSS-Image-SFT-GGUF", ModelRole.DIFFUSION, "MOSS-Image-SFT-Q4_0.gguf")
            files += file("unsloth/Qwen3.5-9B-GGUF", ModelRole.ENCODER, "Qwen3.5-9B-Q4_K_M.gguf")
            files += file("Comfy-Org/MOSS-Image", ModelRole.VAE, "vae/flux2-vae_small.safetensors")
            files += file("OpenMOSS-Team/MOSS-Image-Base", ModelRole.TOKENIZER, "tokenizer/tokenizer.json")
        } else files += file(starter, ModelRole.CHECKPOINT, "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf")
        if (!isBase) {
            val tags = metadata.getAsJsonArray("tags")?.map { it.asString.lowercase() }.orEmpty()
            require(tags.any { it == "lora" || it == "peft" } || metadata.getAsJsonArray("siblings").any { it.asJsonObject.get("rfilename").asString in listOf("adapter_config.json", "pytorch_lora_weights.safetensors") }) { "This repository is not recognized as a LoRA adapter. Try a supported model link or use Advanced for local files." }
            val candidates = metadata.getAsJsonArray("siblings").map { it.asJsonObject.get("rfilename").asString }
                .filter { it.endsWith(".safetensors") && '/' !in it }
            val chosen = link.file ?: candidates.firstOrNull { it.contains("LoRA-ComfyUI", true) }
                ?: candidates.firstOrNull { it == "pytorch_lora_weights.safetensors" }
                ?: candidates.firstOrNull { it == "adapter_model.safetensors" }
                ?: candidates.singleOrNull()
                ?: error("This repository has several adapters. Paste the link to the adapter file you want.")
            files += file(link.repo, ModelRole.LORA, chosen, link.revision)
        }
        val trigger = if (isBase) "" else card.get("instance_prompt")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            .takeIf { it.length <= 128 && it.none { c -> c.code < 32 } }.orEmpty()
        return ModelSetupPlan(link.repo, link.repo.substringAfter('/'), family, trigger, files).also { it.validate() }
    }
}
