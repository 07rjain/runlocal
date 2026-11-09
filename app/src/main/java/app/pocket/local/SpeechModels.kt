package app.pocket.local

import android.content.Context
import android.os.StatFs
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.CancellationException

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
    val licenseSummary: String
) {
    val downloadBytes: Long get() = files.sumOf { it.size }
}

data class SpeechModelFile(val repo: String, val revision: String, val path: String,
                           override val size: Long, override val digest: String) : PinnedDownload {
    override val hashType = "sha256"
    override val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$path"
    override fun validate() {
        ModelLink.parse(repo)
        ModelLink.checkPath(path)
        require(Regex("[0-9a-f]{40}").matches(revision)) { "Speech model revision must be pinned." }
        require(size in 1..(8L * 1024 * 1024 * 1024)) { "Invalid speech model size." }
        require(Regex("[0-9a-f]{64}").matches(digest)) { "Speech model SHA-256 is missing." }
    }
}

object SpeechModelCatalog {
    // Stable backend IDs allow additional families without changing setup, selection or playback.
    const val WOOF_BACKEND = "woof-numpy-v1"
    private const val WOOF_REVISION = "be702960539777fc3f698e54335276a8391b00f9"
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
    ))
    fun find(id: String): SpeechModelSpec = models.firstOrNull { it.id == id }
        ?: error("This speech model has no installed runtime adapter. Choose a supported model in Models.")
    fun forLink(link: String): SpeechModelSpec? {
        val parsed = runCatching { ModelLink.parse(link) }.getOrNull() ?: return null
        return models.firstOrNull { spec -> spec.files.any { it.repo == parsed.repo } &&
            parsed.revision in listOf("main", spec.revision) &&
            (parsed.file == null || spec.files.any { it.path == parsed.file }) }
    }
}

/** Only the main process owns selection preferences; the speech process receives IDs over IPC. */
class SpeechModelStore(private val context: Context) {
    private val directory = File(context.filesDir, "speech-models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("speech-models", Context.MODE_PRIVATE)
    var selectedId: String
        get() = prefs.getString("selected", "android").orEmpty()
        set(value) { require(value == "android" || SpeechModelCatalog.models.any { it.id == value }); prefs.edit().putString("selected", value).apply() }
    fun voice(spec: SpeechModelSpec): String = prefs.getString("voice-${spec.id}", null)
        ?.takeIf { id -> spec.voices.any { it.id == id } } ?: spec.voices.first().id
    fun selectVoice(spec: SpeechModelSpec, id: String) {
        require(spec.voices.any { it.id == id }); prefs.edit().putString("voice-${spec.id}", id).apply()
    }
    fun modelDirectory(spec: SpeechModelSpec) = File(directory, "${spec.id}/${spec.revision}")
    fun file(spec: SpeechModelSpec, file: SpeechModelFile) = File(modelDirectory(spec), file.path)
    private fun marker(spec: SpeechModelSpec) = File(modelDirectory(spec), "ready")
    private fun signature(spec: SpeechModelSpec) = spec.files.joinToString("\n") { "${it.path}:${it.size}:${it.digest}" }
    fun installed(spec: SpeechModelSpec): Boolean = marker(spec).let { ready ->
        // Direct reads are safe with atomically replaced files in the other process.
        ready.isFile && ready.readText() == signature(spec) && spec.files.all { file(spec, it).length() == it.size }
    }
    fun install(spec: SpeechModelSpec, cancelled: () -> Boolean, progress: (Long, Long, Boolean) -> Unit) {
        withModelLock(spec) { installLocked(spec, cancelled, progress) }
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
                require(StatFs(root.path).availableBytes > needed + 128L * 1024 * 1024) { "Free more storage before downloading speech models." }
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
        try { stream.write(signature(spec).toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    fun remove(spec: SpeechModelSpec) {
        withModelLock(spec) {
            check(modelDirectory(spec).deleteRecursively()) { "Could not remove the app's speech model copy." }
            if (selectedId == spec.id) selectedId = "android"
        }
    }
}
