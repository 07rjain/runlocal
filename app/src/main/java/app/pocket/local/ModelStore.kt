package app.pocket.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LocalModel(val id: String, val name: String, val file: File, val textAdapter: String? = null) {
    val size: Long get() = file.length()
}

/** App-private copies, atomic imports, and no network access. */
class ModelStore(private val context: Context) {
    private val directory = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("pocket", Context.MODE_PRIVATE)
    var selectedId: String?
        get() = prefs.getString("selected", null)
        set(value) { prefs.edit().putString("selected", value).apply() }

    fun models(): List<LocalModel> = directory.listFiles().orEmpty()
        .filter { it.extension in listOf("litertlm", "gguf") }
        .map { file ->
            val id = file.nameWithoutExtension
            val meta = runCatching { JSONObject(File(directory, "$id.meta").readText()) }.getOrNull()
            val name = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: prefs.getString("name_$id", file.name) ?: file.name
            val adapter = meta?.optString("textAdapter")?.takeIf { it == QwenThinking.adapter && file.extension == "litertlm" }
            LocalModel(id, name, file, adapter)
        }
        .sortedBy { it.name.lowercase() }

    fun cached(remote: RemoteModelFile): LocalModel? = models().find { it.id == remote.key && it.size == remote.size }
    fun installRemote(source: File, remote: RemoteModelFile, plan: ModelSetupPlan? = null): LocalModel {
        require(remote.role == ModelRole.TEXT)
        remote.validate()
        cached(remote)?.let { return configureForPlan(it, plan) }
        ModelInspector.validate(source, remote.name, ModelRole.TEXT)
        val destination = File(directory, "${remote.key}.${remote.name.substringAfterLast('.')}")
        val model = saveMetadata(configuredModel(LocalModel(remote.key, remote.name, destination), plan))
        check(source.renameTo(destination)) { "Could not save the text model." }
        return model
    }

    internal fun configureForPlan(model: LocalModel, plan: ModelSetupPlan?): LocalModel {
        val configured = configuredModel(model, plan)
        return if (configured != model) saveMetadata(configured) else model
    }

    private fun configuredModel(model: LocalModel, plan: ModelSetupPlan?): LocalModel {
        val adapter = plan?.textAdapter ?: return model
        require(adapter == QwenThinking.adapter && plan.files.single().key == model.id && model.file.extension == "litertlm")
        return model.copy(name = plan.title, textAdapter = adapter)
    }

    private fun saveMetadata(model: LocalModel): LocalModel {
        val metadata = AtomicFile(File(directory, "${model.id}.meta"))
        val output = metadata.startWrite()
        try {
            val json = JSONObject().put("name", model.name).put("textAdapter", model.textAdapter)
            output.write(json.toString().toByteArray())
            metadata.finishWrite(output)
        }
        catch (e: Exception) { metadata.failWrite(output); throw e }
        return model
    }

    fun selected(): LocalModel? = models().find { it.id == selectedId }

    fun import(uri: Uri, progress: (Long) -> Unit): LocalModel {
        var name = "model.litertlm"
        var expected = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) expected = cursor.getLong(sizeColumn)
            }
        }
        require(name.endsWith(".litertlm", ignoreCase = true)) {
            "Choose a .litertlm language model. GGUF, .task and Safetensors files need different runtimes. A LoRA is not a standalone model."
        }
        val reserve = 256L * 1024 * 1024
        require(expected < 0 || StatFs(directory.path).availableBytes > expected + reserve) {
            "Not enough storage. Keep room for a full copy of the model plus 256 MB."
        }
        val id = UUID.randomUUID().toString()
        val partial = File(directory, "$id.part")
        val destination = File(directory, "$id.litertlm")
        try {
            var copied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(StatFs(directory.path).availableBytes > count + reserve) { "Storage is almost full. Import stopped safely." }
                        output.write(buffer, 0, count)
                        copied += count
                        progress(copied)
                    }
                    output.fd.sync()
                }
            } ?: error("This file could not be opened.")
            require(copied > 0 && (expected < 0 || copied == expected)) { "The model file is empty or incomplete." }
            // LiteRT-LM containers begin with an eight-byte magic identifier.
            val header = ByteArray(8)
            partial.inputStream().use { require(it.read(header) == 8) { "The model file is too short." } }
            require(String(header, Charsets.US_ASCII) == "LITERTLM") { "This is not a LiteRT-LM model container. Renaming a file does not convert it." }
            check(partial.renameTo(destination)) { "Could not finish saving the model." }
            prefs.edit().putString("name_$id", name).apply()
            selectedId = id
            return LocalModel(id, name, destination)
        } finally { partial.delete() }
    }

    fun delete(model: LocalModel) {
        check(model.file.delete()) { "Could not delete the model." }
        File(directory, "${model.id}.meta").delete()
        prefs.edit().remove("name_${model.id}").apply()
        if (selectedId == model.id) selectedId = null
    }

    fun loadHistory(): MutableList<ChatLine> = runCatching {
        val array = JSONArray(AtomicFile(File(context.filesDir, "chat.json")).readFully().toString(Charsets.UTF_8))
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let { ChatLine(it.getString("role"), it.getString("text")) }
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun saveHistory(lines: List<ChatLine>) {
        val array = JSONArray()
        lines.forEach { array.put(JSONObject().put("role", it.role).put("text", it.text)) }
        val atomic = AtomicFile(File(context.filesDir, "chat.json"))
        val stream = atomic.startWrite()
        try { stream.write(array.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
}

data class ChatLine(
    val role: String, val text: String,
    val id: String = UUID.randomUUID().toString(),
    val image: String = "", val state: String = "complete",
    val turn: String = "",
    val metrics: GenerationMetrics? = null,
    val reasoning: String? = null
)
