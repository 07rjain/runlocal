package app.pocket.local

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.Strictness
import java.security.MessageDigest

/** A recipe selects bundled inference code and supplies only pinned data files. */
internal enum class RecipeAdapter(
    val key: String,
    val backend: String,
    val task: ModelTask,
    val memoryMiB: Long,
    val files: Map<String, String>
) {
    WOOF("woof-v1", SpeechModelCatalog.WOOF_BACKEND, ModelTask.SPEAK, 900, mapOf("weights" to "woof_int8.npz")),
    PICO("kitten-pico-en-v1", SpeechModelCatalog.KITTEN_PICO_BACKEND, ModelTask.SPEAK, 256,
        mapOf("model" to "kitten_tts_2_pico_en_int4.onnx", "voices" to "voices_en.npz",
            "vocabulary" to "vocab.json", "dictionary" to "g2p/cmu_misaki_us.json")),
    KOKORO("sherpa-kokoro-v1", "sherpa-kokoro-v1", ModelTask.SPEAK, 512, mapOf(
        "model" to "model.onnx", "voices" to "voices.bin", "tokens" to "tokens.txt", "lexicon" to "lexicon.txt")),
    PIPER("sherpa-piper-v1", "sherpa-piper-v1", ModelTask.SPEAK, 448, mapOf(
        "model" to "model.onnx", "tokens" to "tokens.txt", "lexicon" to "lexicon.txt")),
    BLUE("bluev3-en-v1", "bluev3-en-v1", ModelTask.SPEAK, 1024, mapOf(
        "duration" to "duration.onnx", "encoder" to "encoder.onnx", "flow" to "flow.onnx", "vocoder" to "vocoder.onnx",
        "voice" to "voice.json", "vocabulary" to "vocab.json", "lexicon" to "en.lex", "pronunciation" to "en-large.g2p")),
    WHISPER("sherpa-whisper-v1", "sherpa-whisper-v1", ModelTask.DICTATE, 768, mapOf(
        "encoder" to "encoder.onnx", "decoder" to "decoder.onnx", "tokens" to "tokens.txt")),
    ZIPFORMER("sherpa-zipformer-v1", "sherpa-zipformer-v1", ModelTask.DICTATE, 512, mapOf(
        "encoder" to "encoder.onnx", "decoder" to "decoder.onnx", "joiner" to "joiner.onnx", "tokens" to "tokens.txt")),
    TESSERACT("tesseract-lstm-v1", "tesseract-lstm-v1", ModelTask.OCR, 256, mapOf("language" to ".traineddata"));

    fun read(id: String, title: String, license: String, entry: JsonObject): SpeechModelSpec {
        val language = entry.optionalString("language", 16).orEmpty()
        val inputFiles = entry.getAsJsonObject("files")
        require(inputFiles != null && inputFiles.keySet() == files.keys) { "$key requires ${files.keys.joinToString()}." }
        val downloads = files.map { (role, basename) ->
            val file = inputFiles.getAsJsonObject(role)
            val path = file.string("path", 512)
            val local = if (this == TESSERACT) {
                require(language.matches(Regex("[a-z]{3}(_[a-zA-Z0-9]{1,8})?"))) { "Use the exact Tesseract language code." }
                "tessdata/$language.traineddata"
            } else basename
            SpeechModelFile(file.string("repo", 193), file.string("revision", 40), path, file.integer("bytes"),
                file.string("sha256", 64), localPath = local)
        }
        val voiceEntries = entry.getAsJsonArray("voices")
        val voices = voiceEntries?.map { item ->
            val voice = item.asJsonObject
            SpeechVoice(voice.string("id", 24), voice.string("name", 60), voice.string("language", 40))
        }.orEmpty()
        val first = downloads.first().also { it.validate() }
        val revision = fingerprint(backend, language, downloads, voices)
        val woof = if (this == WOOF) SpeechModelCatalog.find("underdog-woof-v1") else null
        val spec = SpeechModelSpec("$id-$revision", title, backend, revision, downloads, voices, memoryMiB * MIB,
            "https://huggingface.co/${first.repo}/tree/${first.revision}",
            woof?.credit ?: "Model by ${first.repo.substringBefore('/')}", woof?.creditUrl ?: "https://huggingface.co/${first.repo}",
            woof?.licenseSummary ?: license, language, id)
        validate(spec)
        return spec
    }

    private fun validate(spec: SpeechModelSpec) {
        val slug = requireNotNull(spec.catalogId)
        require(slug.matches(Regex("[a-z0-9][a-z0-9.-]{0,63}")))
        require(spec.backend == backend && spec.memoryBytes == memoryMiB * MIB)
        val expectedPaths = if (this == TESSERACT) {
            require(spec.language.matches(Regex("[a-z]{3}(_[a-zA-Z0-9]{1,8})?")))
            listOf("tessdata/${spec.language}.traineddata")
        } else files.values.toList()
        require(spec.files.map { it.localPath } == expectedPaths)
        spec.files.forEach { file ->
            file.validate()
            require(file.hashType == "sha256" && file.path.endsWith(".${requireNotNull(file.localPath).substringAfterLast('.')}")) {
                "The files for $key must have the expected format and SHA-256."
            }
        }
        require(spec.revision == fingerprint(backend, spec.language, spec.files, spec.voices) && spec.id == "$slug-${spec.revision}")
        require(spec.voices.size <= 64 && spec.voices.map { it.id }.distinct().size == spec.voices.size)
        val voiceIds = spec.voices.map { it.id }
        when (this) {
            WOOF -> require(voiceIds.isNotEmpty() && voiceIds.all { it in listOf("juno", "mira") })
            PICO -> require(voiceIds.isNotEmpty() && voiceIds.all { it in listOf("eleanor", "edith", "otis", "frank") })
            KOKORO, PIPER -> require(voiceIds.isNotEmpty() && voiceIds.all { id -> id.toIntOrNull()?.let { it in 0..4095 } == true })
            BLUE -> require(voiceIds.size == 1 && voiceIds.single().matches(Regex("[a-z0-9-]+")) && spec.language == "en")
            else -> require(voiceIds.isEmpty()) { "$key does not use voices." }
        }
        if (this != TESSERACT) require(spec.language.matches(Regex("[a-z]{0,3}")))
    }

    companion object {
        private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
        fun forKey(key: String) = entries.firstOrNull { it.key == key }
        fun forBackend(backend: String) = entries.firstOrNull { it.backend == backend }
        fun isLocalId(id: String) = id.matches(Regex("[a-z0-9][a-z0-9.-]{0,63}-[a-f0-9]{64}"))
        fun encode(spec: SpeechModelSpec): String {
            checkNotNull(forBackend(spec.backend)).validate(spec)
            return gson.toJson(spec)
        }
        fun decode(json: String): SpeechModelSpec = requireNotNull(gson.fromJson(json, SpeechModelSpec::class.java)).also {
            checkNotNull(forBackend(it.backend)).validate(it)
        }
        private fun fingerprint(backend: String, language: String, files: List<SpeechModelFile>, voices: List<SpeechVoice>): String {
            val encoded = (listOf(backend, language) + files.map { "${it.localPath}:${it.size}:${it.digest}" } +
                voices.map { it.id }.sorted()).joinToString("\n")
            return MessageDigest.getInstance("SHA-256").digest(encoded.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
