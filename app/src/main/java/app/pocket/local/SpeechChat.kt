package app.pocket.local

/** A voice is part of the chat's model identity, so replay never substitutes another voice. */
fun speechChatKey(model: SpeechModelSpec, voice: SpeechVoice) = "speech:${model.id}:${voice.id}"

fun speechChatSelection(key: String): Pair<SpeechModelSpec, SpeechVoice>? =
    SpeechModelCatalog.models.firstNotNullOfOrNull { model ->
        model.voices.firstOrNull { speechChatKey(model, it) == key }?.let { model to it }
    }

/** Completion stays with the originating conversation, including cancellation before playback. */
class SpeechChatTurn(private val chats: ChatRepository) {
    private data class Pending(val chatId: String, val replyId: String, val text: String)
    private var pending: Pending? = null
    fun begin(chatId: String, replyId: String, text: String) {
        check(pending == null) { "Finish the previous speech request first." }
        pending = Pending(chatId, replyId, text)
    }
    fun event(state: String, message: String): Boolean {
        val reply = pending ?: return false
        if (state !in listOf("playing", "complete", "error", "stopped")) return false
        pending = null
        return chats.finish(reply.chatId, reply.replyId,
            if (state == "playing" || state == "complete") reply.text else message,
            failed = state == "error" || state == "stopped")
    }
}
