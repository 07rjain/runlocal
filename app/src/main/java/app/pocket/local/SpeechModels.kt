package app.pocket.local

import android.content.Context
import android.os.StatFs
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.CancellationException

internal const val SPEECH_STORAGE_RESERVE = 128L * MIB

data class SpeechVoice(val id: String, val name: String, val language: String)

/** A supported model family names a bundled adapter, never downloaded executable code. */
data class SpeechModelSpec(
    val id: String,
    val title: String,
    val backend: String,
    val revision: String,
    val files: List<SpeechModelFile>,
    val voices: List<SpeechVoice>,
    val memoryBytes: Long,
    val modelUrl: String,
    val credit: String,
    val creditUrl: String,
    val licenseSummary: String,
    val language: String = "",
    val catalogId: String? = null
) {
    val downloadBytes: Long get() = files.sumOf { it.size }
}

data class SpeechModelFile(val repo: String, val revision: String, val path: String,
                           override val size: Long, override val digest: String,
                           override val hashType: String = "sha256",
                           val localPath: String? = null) : PinnedDownload {
    override val url: String get() = URI("https", "huggingface.co", "/$repo/resolve/$revision/$path", null).toASCIIString()
    override fun validate() {
        require(ModelLink.parse(repo).repo == repo)
        ModelLink.checkPath(path)
        localPath?.let(ModelLink::checkPath)
        require(Regex("[0-9a-f]{40}").matches(revision)) { "Speech model revision must be pinned." }
        require(size in 1..(8L * 1024 * 1024 * 1024)) { "Invalid speech model size." }
        require((hashType == "sha256" && Regex("[0-9a-f]{64}").matches(digest)) ||
            (hashType == "git-sha1" && Regex("[0-9a-f]{40}").matches(digest))) { "Speech model integrity metadata is missing." }
    }
}

object SpeechModelCatalog {
    // Stable backend IDs allow additional families without changing setup, selection or playback.
    const val WOOF_BACKEND = "woof-numpy-v1"
    const val KITTEN_PICO_BACKEND = "kitten-2-pico-en-int4"
    private const val WOOF_REVISION = "be702960539777fc3f698e54335276a8391b00f9"
    private const val PICO_REPO = "KittenML/kitten-tts-2-pico"
    private const val PICO_REVISION = "a5632deac7d92f6178db814d933eba746a97f2dc"
    private fun picoFile(path: String, size: Long, hash: String, hashType: String = "sha256") =
        SpeechModelFile(PICO_REPO, PICO_REVISION, path, size, hash, hashType)
    val models = listOf(SpeechModelSpec(
        id = "underdog-woof-v1", title = "Underdog Woof", backend = WOOF_BACKEND,
        revision = WOOF_REVISION,
        files = listOf(SpeechModelFile("ConwayResearch/Underdog-Woof", WOOF_REVISION, "woof_int8.npz",
            40_595_426, "d895fbbe84b8f4f461e3e94ab48b1164353b1988ce8d365f5aa8263d0de1203e")),
        voices = listOf(SpeechVoice("juno", "Juno", "English"), SpeechVoice("mira", "Mira", "English")),
        memoryBytes = 900L * 1024 * 1024,
        modelUrl = "https://huggingface.co/ConwayResearch/Underdog-Woof",
        credit = "Voice by Underdog Woof", creditUrl = "https://underdog.ai",
        licenseSummary = "Free for non-commercial use with credit. Commercial use needs a separate license. Published audio must credit Underdog Woof."
    ), SpeechModelSpec(
        id = KITTEN_PICO_BACKEND, title = "Kitten TTS 2 Pico", backend = KITTEN_PICO_BACKEND,
        revision = PICO_REVISION,
        files = listOf(
            picoFile("kitten_tts_2_pico_en_int4.onnx", 3_969_655, "9751511ba5d5311dd8557967af92bf437f57dfe490bf8a2c135d8f85359b4f58"),
            picoFile("voices_en.npz", 2_089_952, "48496124eef320b70ccf741d871e401889597d5e14e15e6b2116bdc6100f1a0c"),
            picoFile("vocab.json", 1_313, "d480c3e5f5ad4408ac3ddcf6e118feed69e8be3d", "git-sha1"),
            picoFile("g2p/cmu_misaki_us.json", 3_418_388, "d7e24d3f5bd6647a5c0598b33265fc898b5007b7", "git-sha1")
        ),
        voices = listOf("Eleanor", "Edith", "Otis", "Frank").map { SpeechVoice(it.lowercase(), it, "English") },
        memoryBytes = 256L * MIB,
        modelUrl = "https://huggingface.co/$PICO_REPO",
        credit = "Voice by KittenML", creditUrl = "https://github.com/KittenML/KittenTTS",
        licenseSummary = "Apache 2.0 model. The English pronunciation dictionary is based on CMUdict."
    ))
    fun find(id: String): SpeechModelSpec = models.firstOrNull { it.id == id }
        ?: error("This speech model has no installed runtime adapter. Choose a supported model in Models.")
    fun forLink(link: String): SpeechModelSpec? {
        val parsed = runCatching { ModelLink.parse(link) }.getOrNull() ?: return null
        return models.firstOrNull { spec -> spec.files.any {
            it.repo.equals(parsed.repo, ignoreCase = true) && parsed.revision in listOf("main", it.revision) &&
                (parsed.file == null || it.path == parsed.file)
        } }
    }
}

/** Only the main process owns selection preferences; the speech process receives IDs over IPC. */
class SpeechModelStore(private val context: Context) {
    private val directory = File(context.filesDir, "speech-models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("speech-models", Context.MODE_PRIVATE)
    var selectedId: String
        get() = prefs.getString("selected", "android").orEmpty()
        set(value) { require(value == "android" || recipes().any { it.id == value && it.voices.isNotEmpty() }); prefs.edit().putString("selected", value).apply() }
    fun voice(spec: SpeechModelSpec): String = prefs.getString("voice-${spec.id}", null)
        ?.takeIf { id -> spec.voices.any { it.id == id } } ?: spec.voices.first().id
    fun selectVoice(spec: SpeechModelSpec, id: String) {
        require(spec.voices.any { it.id == id }); prefs.edit().putString("voice-${spec.id}", id).apply()
    }
    fun modelDirectory(spec: SpeechModelSpec) = File(directory, "${spec.id}/${spec.revision}")
    fun file(spec: SpeechModelSpec, file: SpeechModelFile) = File(modelDirectory(spec), file.localPath ?: file.path)
    fun recipes(): List<SpeechModelSpec> = SpeechModelCatalog.models + directory.listFiles().orEmpty()
        .filter { it.extension == "json" && RecipeAdapter.isLocalId(it.nameWithoutExtension) }
        .mapNotNull { file ->
            runCatching {
                require(file.length() in 1..65536)
                RecipeAdapter.decode(file.readText()).also { require(it.id == file.nameWithoutExtension) }
            }.getOrNull()
        }
    fun find(id: String): SpeechModelSpec = recipes().find { it.id == id }
        ?: error("This model recipe is no longer installed. Download it again from Models.")
    private fun marker(spec: SpeechModelSpec) = File(modelDirectory(spec), "ready")
    fun remainingDownloadBytes(spec: SpeechModelSpec) = spec.files.sumOf { remote ->
        val destination = file(spec, remote)
        val partial = File(destination.path + ".part")
        (remote.size - (if (partial.isFile) partial else destination).length()).coerceAtLeast(0)
    }
    fun installed(spec: SpeechModelSpec): Boolean = marker(spec).let { ready ->
        // Direct reads are safe with atomically replaced files in the other process.
        val signature = try { ready.readText() } catch (_: java.io.FileNotFoundException) { return false }
        signature == speechReadySignature(spec) && spec.files.all { file(spec, it).length() == it.size }
    }
    fun install(spec: SpeechModelSpec, cancelled: () -> Boolean, progress: (Long, Long, Boolean) -> Unit) {
        withModelLock(spec) {
            if (installed(spec)) return@withModelLock
            if (spec.catalogId != null) {
                val json = RecipeAdapter.encode(spec)
                val metadata = AtomicFile(File(directory, "${spec.id}.json"))
                val stream = metadata.startWrite()
                try { stream.write(json.encodeToByteArray()); metadata.finishWrite(stream) }
                catch (error: Exception) { metadata.failWrite(stream); throw error }
            }
            installLocked(spec, cancelled, progress)
        }
    }
    private fun <T> withModelLock(spec: SpeechModelSpec, operation: () -> T): T {
        // A stopped Activity may still be leaving a network read. A new Activity must not
        // append to the same partial file until that writer has released it.
        return RandomAccessFile(File(directory, "${spec.id}.lock"), "rw").use { handle ->
            val lock = try { handle.channel.tryLock() }
            catch (error: OverlappingFileLockException) { throw IllegalStateException("Speech setup is still pausing. Try again in a moment.", error) }
            checkNotNull(lock) { "Another speech setup is using these files. Try again in a moment." }.use { operation() }
        }
    }
    private fun installLocked(spec: SpeechModelSpec, cancelled: () -> Boolean, progress: (Long, Long, Boolean) -> Unit) {
        val root = modelDirectory(spec).apply { check(mkdirs() || isDirectory) { "Could not create speech model storage." } }
        var completed = 0L
        spec.files.forEach { remote ->
            if (cancelled()) throw CancellationException("Speech model download paused.")
            val destination = file(spec, remote)
            destination.parentFile?.mkdirs()
            val partial = File(destination.path + ".part")
            // A complete file is reverified if setup was interrupted before the ready marker.
            if (destination.isFile && !partial.exists()) check(destination.renameTo(partial)) { "Could not verify speech model." }
            ModelFileDownloader().download(remote, partial, checkSpace = { needed ->
                if (cancelled()) throw CancellationException("Speech model download paused.")
                require(StatFs(root.path).availableBytes > needed + SPEECH_STORAGE_RESERVE) { "Free more storage before downloading speech models." }
            }, progress = { bytes, verifying ->
                if (cancelled()) throw CancellationException("Speech model download paused.")
                progress(completed + bytes, spec.downloadBytes, verifying)
            })
            if (cancelled()) throw CancellationException("Speech model download paused.")
            check(partial.renameTo(destination)) { "Could not finish saving the speech model." }
            completed += remote.size
        }
        val atomic = AtomicFile(marker(spec))
        val stream = atomic.startWrite()
        try { stream.write(speechReadySignature(spec).toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    fun remove(spec: SpeechModelSpec) {
        withModelLock(spec) {
            check(modelDirectory(spec).deleteRecursively()) { "Could not remove the app's speech model copy." }
            if (selectedId == spec.id) selectedId = "android"
        }
    }
}

internal fun speechReadySignature(spec: SpeechModelSpec) = spec.files.joinToString("\n") { file ->
    // Editable recipes can move identical verified bytes to a new publisher path.
    val path = if (spec.catalogId == null) file.path else requireNotNull(file.localPath)
    "$path:${file.size}:${file.digest}"
}
