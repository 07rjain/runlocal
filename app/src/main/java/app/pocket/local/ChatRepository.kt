package app.pocket.local

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class SavedChat(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New chat",
    var model: String = "",
    var modelLabel: String = "",
    var imageAssets: Map<String, String> = emptyMap(),
    var trigger: String = "",
    var draft: String = "",
    var messages: MutableList<ChatLine> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis()
)

/** One atomic file per conversation. Selection changes never redirect an in-flight reply. */
class ChatRepository(private val directory: File) {
    private val gson = Gson()
    init { check(directory.isDirectory || directory.mkdirs()) }
    private fun file(id: String): File {
        require(Regex("[a-zA-Z0-9-]{1,80}").matches(id))
        return File(directory, "$id.json")
    }
    fun all(): List<SavedChat> = directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
        runCatching { gson.fromJson(it.readText(), SavedChat::class.java).also { chat -> require(file(chat.id).name == it.name) } }.getOrNull()
    }.sortedByDescending { it.updatedAt }
    fun get(id: String): SavedChat? = runCatching { gson.fromJson(file(id).readText(), SavedChat::class.java) }.getOrNull()
    fun save(chat: SavedChat) {
        val destination = file(chat.id)
        val temporary = File(directory, "${chat.id}.new")
        try {
            FileOutputStream(temporary).use { it.write(gson.toJson(chat).toByteArray()); it.fd.sync() }
            try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
    fun create(model: String = "", label: String = ""): SavedChat = SavedChat(model = model, modelLabel = label).also { save(it); select(it.id) }
    fun select(id: String) { require(get(id) != null); File(directory, "selected").writeText(id) }
    fun selected(): SavedChat? = runCatching { get(File(directory, "selected").readText()) }.getOrNull()
    fun delete(id: String) { check(file(id).delete()) { "Could not delete this conversation." } }
    fun finish(chatId: String, replyId: String, text: String, image: String = "", failed: Boolean = false, metrics: GenerationMetrics? = null): Boolean {
        val chat = get(chatId) ?: return false
        val index = chat.messages.indexOfFirst { it.id == replyId && it.state == "pending" }
        if (index < 0) return false
        chat.messages[index] = chat.messages[index].copy(text = text, image = image, state = if (failed) "error" else "complete", metrics = if (failed) null else metrics)
        chat.updatedAt = System.currentTimeMillis(); save(chat)
        return true
    }
}

/** Failed turns and in-progress assistant placeholders must never become future model context. */
fun textConversationContext(messages: List<ChatLine>): List<ChatLine> {
    val failedTurns = messages.filter { it.state == "error" }.map { it.turn }.toSet()
    return messages.filter { it.image.isEmpty() && it.state == "complete" && it.turn !in failedTurns }
}
