package app.pocket.local

interface TextRuntime : AutoCloseable {
    fun generate(model: LocalModel, history: List<ChatLine>, backend: ProcessingBackend = ProcessingBackend.CPU, conversationId: String = "default"): String
}

interface TextSession : AutoCloseable { fun send(prompt: String): String }
fun interface TextSessionFactory {
    fun open(model: LocalModel, backend: ProcessingBackend, history: List<ChatLine>): TextSession
}

/** Called only on the serial inference worker. A native engine never serves two chats. */
class LocalEngine(cachePath: String, private val sessions: TextSessionFactory = LiteRtTextSessionFactory(cachePath)) : TextRuntime {
    private data class SessionKey(val modelId: String, val path: String, val backend: ProcessingBackend, val conversationId: String)
    private var session: TextSession? = null
    private var key: SessionKey? = null
    private var completedHistory: List<Pair<String, String>> = emptyList()

    override fun generate(model: LocalModel, history: List<ChatLine>, backend: ProcessingBackend, conversationId: String): String {
        require(history.isNotEmpty() && history.last().role == "user") { "A conversation must end with your message." }
        val requested = SessionKey(model.id, model.file.absolutePath, backend, conversationId)
        val prior = history.dropLast(1)
        val replay = recentTextHistory(prior)
        val signature = replay.map { it.role to it.text }
        try {
            // LFM3 retains convolution state in the Engine. Recreating only Conversation
            // would leak that state between chats or duplicate replayed messages.
            if (session == null || key != requested || signature != completedHistory) {
                close()
                session = sessions.open(model, backend, replay)
                key = requested
            }
            val response = checkNotNull(session) { "Text session did not initialize." }.send(history.last().text)
            check(response.isNotBlank()) { "The model returned no answer text. Try a shorter prompt or another compatible model." }
            completedHistory = signature + ("user" to history.last().text) + ("assistant" to response)
            return response
        } catch (error: Throwable) {
            // A failed decode can leave native state partially advanced. Never reuse it.
            runCatching { close() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    override fun close() {
        val previous = session
        session = null; key = null; completedHistory = emptyList()
        previous?.close()
    }
}

/** Bounded replay after restart/backend/chat switches; keep complete user/assistant pairs. */
fun recentTextHistory(history: List<ChatLine>): List<ChatLine> = history.takeLast(6).dropWhile { it.role != "user" }
