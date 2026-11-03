package app.pocket.local

import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

enum class PhoneModelKind(val label: String, val canImport: Boolean = false) {
    LITERT("LiteRT language model", true), IMAGE_CHECKPOINT("Image checkpoint candidate", true),
    IMAGE_LORA("Image LoRA", true), GGUF("GGUF model · compatibility not verified"),
    LANGUAGE_LORA("Language LoRA · another runtime required"),
    SAFETENSORS("Safetensors weights · compatibility not verified"),
    CACTUS_BUNDLE("Cactus model bundle · Cactus engine required"),
    CACTUS_FILE("Cactus weights · Cactus engine required"),
    CACTUS_ARCHIVE("Cactus archive candidate · not directly runnable"),
    TASK("MediaPipe task · another runtime required"), ONNX("ONNX file · another runtime required")
}

data class PhoneDocument(val key: String, val uri: String, val name: String, val directory: Boolean,
                         val size: Long = -1, val modified: Long = 0)
data class FoundPhoneModel(val key: String, val uri: String, val name: String, val kind: PhoneModelKind,
                           val size: Long, val location: String, val familyHint: String = "", val modified: Long = 0)
data class PhoneModelScan(val models: List<FoundPhoneModel>, val warnings: List<String>, val limited: Boolean)

interface PhoneDocuments {
    fun children(directory: PhoneDocument): List<PhoneDocument>
    fun prefix(file: PhoneDocument, maximum: Int): ByteArray
}

object PhoneModelFormats {
    private val extensions = setOf("gguf", "litertlm", "safetensors", "task", "onnx", "cact", "cactus", "bin", "zip")
    fun candidate(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return !lower.startsWith('.') && lower.substringAfterLast('.', "") in extensions
    }
    fun cactusFolder(name: String, children: List<PhoneDocument>, cactusMagic: Boolean): Boolean {
        val names = children.map { it.name.lowercase(Locale.ROOT) }.toSet()
        return name.endsWith(".cact", true) || name.endsWith(".cactus", true) ||
            ("config.txt" in names && children.any { it.directory && it.name.equals("components", true) }) ||
            (cactusMagic && ("config.json" in names || "config.txt" in names))
    }
    fun detect(name: String, bytes: ByteArray): Pair<PhoneModelKind, String>? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val first = bytes.take(8).toByteArray().toString(Charsets.US_ASCII)
        if (extension == "litertlm") return if (first == "LITERTLM") PhoneModelKind.LITERT to "" else null
        if (extension == "gguf") return if (first.startsWith("GGUF")) PhoneModelKind.GGUF to "" else null
        if (extension in listOf("bin", "cact", "cactus") && first.startsWith("CACT")) return PhoneModelKind.CACTUS_FILE to ""
        if (extension in listOf("cact", "cactus")) return PhoneModelKind.CACTUS_FILE to ""
        if (extension == "zip" && (name.contains("cactus", true) || Regex(".*-(cq|int)[1248].*\\.zip", RegexOption.IGNORE_CASE).matches(name)) && first.startsWith("PK"))
            return PhoneModelKind.CACTUS_ARCHIVE to ""
        if (extension == "task" && first.startsWith("PK")) return PhoneModelKind.TASK to ""
        if (extension == "onnx" && bytes.isNotEmpty()) return PhoneModelKind.ONNX to ""
        if (extension != "safetensors" || bytes.size < 8) return null
        val length = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (length !in 2..(16L * 1024 * 1024)) return null
        if (length > bytes.size - 8) return PhoneModelKind.SAFETENSORS to ""
        return runCatching {
            val header = JsonParser.parseString(bytes.copyOfRange(8, 8 + length.toInt()).toString(Charsets.UTF_8)).asJsonObject
            val keys = header.keySet().filter { it != "__metadata__" }
            if (keys.isEmpty()) return@runCatching null
            val moss = keys.any { (it.startsWith("diffusion_model.layers.") || it.startsWith("transformer.layers.")) && it.contains(".proj.") }
            val imageKeys = moss || keys.any { it.startsWith("lora_unet_") || it.startsWith("unet.") || it.startsWith("model.diffusion_model.") || it.startsWith("diffusion_model.") }
            when {
                !imageKeys && keys.any { it.contains("lora", true) } && keys.any { it.startsWith("base_model.model.") || it.contains(".self_attn.") } -> PhoneModelKind.LANGUAGE_LORA to ""
                keys.any { it.contains("lora_", true) || it.contains(".lora", true) } -> PhoneModelKind.IMAGE_LORA to if (moss) "moss" else ""
                keys.any { it.startsWith("model.diffusion_model.") } && keys.any { it.startsWith("first_stage_model.") } && keys.any { it.startsWith("cond_stage_model.") } -> PhoneModelKind.IMAGE_CHECKPOINT to "sd15"
                else -> PhoneModelKind.SAFETENSORS to ""
            }
        }.getOrNull()
    }
}

/** Bounded metadata/header discovery. Never copies weights, extracts archives or writes into a source folder. */
class PhoneModelScanner(private val documents: PhoneDocuments, private val maxItems: Int = 5000,
                        private val maxDepth: Int = 12, private val maxHeaderBytes: Int = 16 * 1024 * 1024) {
    fun scan(root: PhoneDocument): PhoneModelScan {
        data class Pending(val doc: PhoneDocument, val depth: Int, val path: String, val bundle: String?)
        val queue = java.util.ArrayDeque<Pending>()
        queue.add(Pending(root, 0, root.name, null))
        val visited = mutableSetOf<String>()
        val found = linkedMapOf<String, FoundPhoneModel>()
        val sizes = mutableMapOf<String, Long>()
        val unknownSizes = mutableSetOf<String>()
        val warnings = linkedSetOf<String>()
        var items = 0; var headerBytes = 0; var limited = false
        fun read(doc: PhoneDocument): ByteArray {
            val remaining = maxHeaderBytes - headerBytes
            if (remaining < 8) { limited = true; return byteArrayOf() }
            return try { documents.prefix(doc, minOf(128 * 1024, remaining)).also { headerBytes += it.size } }
            catch (_: Exception) { warnings.add("Some files could not be read. Reconnect the folder or refresh after the download finishes."); byteArrayOf() }
        }
        fun addFile(doc: PhoneDocument, path: String, prefix: ByteArray? = null) {
            if (!PhoneModelFormats.candidate(doc.name) || doc.size == 0L) return
            val type = PhoneModelFormats.detect(doc.name, prefix ?: read(doc)) ?: return
            found[doc.key] = FoundPhoneModel(doc.key, doc.uri, doc.name, type.first, doc.size, path, type.second, doc.modified)
        }
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            if (!visited.add(item.doc.key)) continue
            if (++items > maxItems) { limited = true; break }
            if (!item.doc.directory) { addFile(item.doc, item.path); continue }
            val children = try { documents.children(item.doc) } catch (_: Exception) {
                warnings.add("A folder could not be read. Choose it again to restore access."); continue
            }
            var bundle = item.bundle
            val sample = children.firstOrNull { !it.directory && it.name.endsWith(".bin", true) }
            val sampleBytes = if (bundle == null && sample != null && children.any { it.name.lowercase(Locale.ROOT) in listOf("config.json", "config.txt") }) read(sample) else null
            if (bundle == null && PhoneModelFormats.cactusFolder(item.doc.name, children, sampleBytes?.take(4)?.toByteArray()?.toString(Charsets.US_ASCII) == "CACT")) {
                bundle = item.doc.key
                found[bundle] = FoundPhoneModel(bundle, item.doc.uri, item.doc.name, PhoneModelKind.CACTUS_BUNDLE, -1, item.path)
                sizes[bundle] = 0
            }
            for (child in children) {
                if (child.name.startsWith('.')) continue
                if (child.directory) {
                    if (item.depth >= maxDepth) { limited = true; continue }
                    queue.add(Pending(child, item.depth + 1, "${item.path}/${child.name}", bundle))
                } else {
                    if (++items > maxItems) { limited = true; break }
                    if (bundle != null) {
                        if (child.size < 0) unknownSizes.add(bundle) else sizes[bundle] = (sizes[bundle] ?: 0) + child.size
                        if (child.name.substringAfterLast('.').lowercase(Locale.ROOT) in listOf("bin", "cact", "cactus", "onnx", "zip")) continue
                    }
                    if (visited.add(child.key)) addFile(child, item.path, if (child.key == sample?.key) sampleBytes else null)
                }
            }
            if (items > maxItems) break
        }
        sizes.forEach { (key, size) -> found[key]?.let { found[key] = it.copy(size = if (key in unknownSizes || limited) -1 else size) } }
        if (limited) warnings.add("Scan limit reached. Choose a smaller model folder to find more files.")
        return PhoneModelScan(found.values.sortedBy { it.name.lowercase(Locale.ROOT) }, warnings.toList(), limited)
    }
}
