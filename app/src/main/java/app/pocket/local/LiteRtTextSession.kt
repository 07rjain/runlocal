package app.pocket.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.channels.Channel

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
        @OptIn(ExperimentalApi::class)
        ExperimentalFlags.enableBenchmark = true
        val engine = Engine(textEngineConfig(model, backend, cachePath))
        try {
            engine.initialize()
            val conversation = engine.createConversation(ConversationConfig(
                initialMessages = history.map { if (it.role == "user") Message.user(it.text) else Message.model(it.text) },
                thinkingConfig = ThinkingConfig(enableThinking = false),
                maxOutputToken = 512
            ))
            return object : TextSession {
                override fun send(prompt: String): String = stream(prompt) {}
                override fun stream(prompt: String, onText: (String) -> Unit): String = runBlocking {
                    val text = StringBuilder()
                    // Collect callbacks on the owning serial worker; a callback chunk may
                    // contain several tokens, or only channel metadata.
                    // The upstream callback uses trySend: avoid losing text during UI/GC
                    // stalls. The native request is capped at 512 output tokens.
                    conversation.sendMessageAsync(prompt).buffer(Channel.UNLIMITED).collect { message ->
                        val delta = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        if (delta.isNotEmpty()) { text.append(delta); onText(text.toString()) }
                    }
                    text.toString()
                }
                @OptIn(ExperimentalApi::class)
                override fun metrics(): DecodeMetrics? = runCatching {
                    conversation.getBenchmarkInfo().let { nativeDecodeMetrics(it.lastDecodeTokenCount, it.lastDecodeTokensPerSecond, it.timeToFirstTokenInSecond) }
                }.getOrNull() // Some model/backend combinations do not expose benchmark info.
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
