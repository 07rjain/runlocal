package app.pocket.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File

internal fun textEngineConfig(model: LocalModel, backend: ProcessingBackend, cachePath: String): EngineConfig {
    // Separate delegate caches, including the runtime version, to avoid stale CPU/GPU artifacts.
    val cache = File(cachePath, "litert-${TextRuntimeInfo.VERSION}/${model.id}/${backend.name.lowercase()}").apply { mkdirs() }
    return EngineConfig(modelPath = model.file.absolutePath,
        backend = if (backend == ProcessingBackend.CPU) Backend.CPU(threadCount = 4) else Backend.GPU(),
        maxNumTokens = 2048, cacheDir = cache.absolutePath)
}

internal class LiteRtTextSessionFactory(private val cachePath: String, private val availableMemory: (() -> Long)? = null) : TextSessionFactory {
    override fun open(model: LocalModel, backend: ProcessingBackend, history: List<ChatLine>): TextSession {
        // Check only when opening an engine. Re-checking a reused session would count
        // its already-loaded weights against free RAM and incorrectly block turn two.
        availableMemory?.invoke()?.let { available ->
            require(model.size <= available * .65) { "Not enough memory to load this model: ${available / (1024 * 1024)} MB free, ${model.size / (1024 * 1024)} MB of weights. Close other apps or choose a smaller model." }
        }
        val engine = Engine(textEngineConfig(model, backend, cachePath))
        try {
            engine.initialize()
            val conversation = engine.createConversation(ConversationConfig(
                initialMessages = history.map { if (it.role == "user") Message.user(it.text) else Message.model(it.text) },
                thinkingConfig = ThinkingConfig(enableThinking = false),
                maxOutputToken = 512
            ))
            return object : TextSession {
                override fun send(prompt: String): String = conversation.sendMessage(prompt).contents.contents
                    .filterIsInstance<Content.Text>().joinToString("") { it.text }
                override fun close() {
                    try { conversation.close() } finally { engine.close() }
                }
            }
        } catch (error: Throwable) {
            if (engine.isInitialized()) runCatching { engine.close() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }
}
