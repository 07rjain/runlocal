package app.pocket.local

interface TextRuntime : AutoCloseable {
    fun generate(model: LocalModel, history: List<ChatLine>, backend: ProcessingBackend = ProcessingBackend.CPU, conversationId: String = "default", onProgress: (GenerationProgress) -> Unit = {}): String
}

interface TextSession : AutoCloseable {
    fun send(prompt: String): String
    fun stream(prompt: String, onText: (String) -> Unit): String = send(prompt).also(onText)
    fun streamReply(prompt: String, onReply: (TextReply) -> Unit): TextReply =
        TextReply(stream(prompt) { onReply(TextReply(it)) })
    fun metrics(): DecodeMetrics? = null
}
data class TextReply(val text: String, val reasoning: String = "")

fun interface TextSessionFactory {
    fun open(model: LocalModel, backend: ProcessingBackend, history: List<ChatLine>): TextSession
}

/** Called only on the serial inference worker. A native engine never serves two chats. */
class LocalEngine(cachePath: String, private val sessions: TextSessionFactory = LiteRtTextSessionFactory(cachePath)) : TextRuntime {
    private data class SessionKey(val modelId: String, val path: String, val textAdapter: String?,
                                  val backend: ProcessingBackend, val conversationId: String)
    private var session: TextSession? = null
    private var key: SessionKey? = null
    private var completedHistory: List<Pair<String, String>> = emptyList()

    override fun generate(model: LocalModel, history: List<ChatLine>, backend: ProcessingBackend, conversationId: String, onProgress: (GenerationProgress) -> Unit): String {
        val timer = GenerationTimer(backend.title)
        require(history.isNotEmpty() && history.last().role == "user") { "A conversation must end with your message." }
        val requested = SessionKey(model.id, model.file.absolutePath, model.textAdapter, backend, conversationId)
        val prior = history.dropLast(1)
        val replay = recentTextHistory(prior)
        val signature = replay.map { it.role to it.text }
        try {
            // LFM3 retains convolution state in the Engine. Recreating only Conversation
            // would leak that state between chats or duplicate replayed messages.
            if (session == null || key != requested || signature != completedHistory) {
                onProgress(timer.progress("Loading model…"))
                val loadStart = timer.elapsed()
                close()
                session = sessions.open(model, backend, replay)
                key = requested
                timer.loadMs = timer.elapsed() - loadStart
            }
            onProgress(timer.progress("Preparing prompt…"))
            val current = checkNotNull(session) { "Text session did not initialize." }
            val reply = current.streamReply(history.last().text) { part ->
                val stage = if (part.text.isBlank() && part.reasoning.isNotBlank()) "Thinking…" else "Generating…"
                onProgress(timer.progress(stage, part.text, part.reasoning))
            }
            check(reply.text.isNotBlank()) { "The model returned no answer text. Try a shorter prompt or another compatible model." }
            val metrics = timer.finish(current.metrics())
            onProgress(timer.progress("Complete", reply.text, reply.reasoning).copy(metrics = metrics))
            completedHistory = signature + ("user" to history.last().text) + ("assistant" to reply.text)
            return reply.text
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
