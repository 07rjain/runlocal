package app.pocket.local

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.CancellationException

internal object MirageModel : PinnedDownload {
    const val title = "Cactus Mirage"
    const val modelUrl = "https://huggingface.co/Cactus-Compute/mirage"
    private const val revision = "3d03cee88c0acbe4e71b94b567ab14e6e55f40c9"
    override val url = "$modelUrl/resolve/$revision/mirage.cact"
    override val size = 21_611_043L
    override val digest = "4249a0f7515272ff76b9414bec17d7e7daf84b15b66c7fb4f17fce1e66f9f237"
    override val hashType = "sha256"
    val supportedAbi get() = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

    override fun validate() {
        require(Regex("[0-9a-f]{40}").matches(revision) && Regex("[0-9a-f]{64}").matches(digest))
        ModelLink.parse(url)
    }

    fun matchesLink(value: String): Boolean {
        val link = runCatching { ModelLink.parse(value) }.getOrNull() ?: return false
        return link.repo.equals("Cactus-Compute/mirage", ignoreCase = true) &&
            link.revision in listOf("main", revision) && (link.file == null || link.file == "mirage.cact")
    }

    fun file(context: Context) = File(context.filesDir, "ocr-models/$revision/mirage.cact")
    private fun partial(context: Context) = File(file(context).path + ".part")
    fun installed(context: Context) = file(context).let { it.isFile && it.length() == size }
    fun hasPartial(context: Context) = partial(context).isFile

    fun install(context: Context, cancelled: () -> Boolean, progress: (Long, Boolean) -> Unit) {
        check(supportedAbi) { "Mirage requires an ARM64 Android build." }
        if (installed(context)) return
        fun checkCancelled() { if (cancelled()) throw CancellationException("Model download paused.") }
        checkCancelled()
        val model = file(context)
        val pending = ModelFileDownloader().download(this, partial(context), checkSpace = { needed ->
            checkCancelled()
            require(StatFs(context.filesDir.path).availableBytes > needed + 64L * 1024 * 1024) {
                "Free more space before downloading Mirage."
            }
        }, progress = { done, verifying ->
            checkCancelled()
            progress(done, verifying)
        })
        checkCancelled()
        check(pending.renameTo(model)) { "Could not finish installing Mirage." }
    }

    fun remove(context: Context) {
        for (file in listOf(file(context), partial(context))) {
            check(!file.exists() || file.delete()) { "Could not remove the Mirage model file." }
        }
    }
}

internal object MirageNative {
    const val maxEdge = 1600
    private val initialized by lazy {
        try {
            System.loadLibrary("pocket_ocr")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    private external fun describe(model: String, rgb: ByteArray, width: Int, height: Int): ByteArray

    fun read(model: File, rgb: ByteArray, width: Int, height: Int): String {
        check(initialized) { "This build does not include the Mirage OCR runtime." }
        return MirageTranscript.format(describe(model.absolutePath, rgb, width, height).toString(Charsets.UTF_8))
    }
}

/** Mirage returns word-sized regions. Group overlapping regions into readable lines, then order left to right. */
internal object MirageTranscript {
    private data class Fragment(val text: String, val left: Int, val top: Int, val bottom: Int)
    private const val MAX_REGIONS = 96

    fun format(json: String): String {
        val text = JsonParser.parseString(json).asJsonObject.getAsJsonArray("text")
            ?: error("The OCR runtime did not return text regions.")
        require(text.size() <= MAX_REGIONS) { "The OCR result contains too many text regions." }
        val fragments = text.mapNotNull { region ->
            val data = region.asJsonObject
            val value = data.get("string").asString.trim()
            if (value.isEmpty()) return@mapNotNull null
            val bounds = data.getAsJsonArray("bbox")
            require(bounds.size() == 4) { "Invalid text region." }
            val coordinates = bounds.map { it.asInt }
            val (left, top, right, bottom) = coordinates
            require(coordinates.all { it in 0..1000 } && left <= right && top < bottom) { "Invalid text region." }
            Fragment(value, left, top, bottom)
        }.sortedWith(compareBy({ it.top + it.bottom }, { it.left }))

        val rows = mutableListOf<MutableList<Fragment>>()
        for (fragment in fragments) {
            val row = rows.lastOrNull()
            val anchor = row?.first()
            if (anchor != null && overlapsLine(anchor, fragment)) row.add(fragment)
            else rows.add(mutableListOf(fragment))
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString(" ") { it.text } }
    }

    private fun overlapsLine(anchor: Fragment, word: Fragment): Boolean {
        val overlap = minOf(anchor.bottom, word.bottom) - maxOf(anchor.top, word.top)
        return overlap * 2 >= minOf(anchor.bottom - anchor.top, word.bottom - word.top)
    }
}
