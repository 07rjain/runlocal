package app.pocket.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Channel as MessageChannel
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
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
        maxNumTokens = if (model.textAdapter == QwenThinking.adapter) QwenThinking.contextTokens else 2048,
        cacheDir = cache.absolutePath)
}

internal class LiteRtTextSessionFactory(private val cachePath: String, private val availableMemory: (() -> Long)? = null) : TextSessionFactory {
    @OptIn(ExperimentalApi::class)
    override fun open(model: LocalModel, backend: ProcessingBackend, history: List<ChatLine>): TextSession {
        // Check only when opening an engine. Re-checking a reused session would count
        // its already-loaded weights against free RAM and incorrectly block turn two.
        availableMemory?.invoke()?.let { available ->
            require(model.size <= available * .65) { "Not enough memory to load this model: ${available / (1024 * 1024)} MB free, ${model.size / (1024 * 1024)} MB of weights. Close other apps or choose a smaller model." }
        }
        if (model.file.extension.equals("gguf", ignoreCase = true)) {
            require(backend == ProcessingBackend.CPU) { "GGUF chat is available on CPU. Select CPU only for this model." }
            return LlamaTextSession(model, history)
        }
        val thinking = model.textAdapter == QwenThinking.adapter
        ExperimentalFlags.enableBenchmark = true
        val engine = Engine(textEngineConfig(model, backend, cachePath))
        try {
            engine.initialize()
            val config = ConversationConfig(
                initialMessages = history.map { if (it.role == "user") Message.user(it.text) else Message.model(it.text) },
                thinkingConfig = if (thinking) ThinkingConfig(true, QwenThinking.thinkingTokens) else ThinkingConfig(false),
                maxOutputToken = if (thinking) QwenThinking.outputTokens else 512,
                samplerConfig = if (thinking) SamplerConfig(topK = 20, topP = .95, temperature = .6) else null,
                channels = if (thinking) listOf(MessageChannel(QwenThinking.channel, QwenThinking.start, QwenThinking.end)) else null,
                chatTemplate = if (thinking) QwenThinking.template else null
            )
            val previousFilter = ExperimentalFlags.filterChannelContentFromKvCache
            val conversation = try {
                if (thinking) ExperimentalFlags.filterChannelContentFromKvCache = true
                engine.createConversation(config)
            } finally { ExperimentalFlags.filterChannelContentFromKvCache = previousFilter }
            return object : TextSession {
                override fun send(prompt: String): String = streamReply(prompt) {}.text
                override fun stream(prompt: String, onText: (String) -> Unit): String =
                    streamReply(prompt) { if (it.text.isNotEmpty()) onText(it.text) }.text

                override fun streamReply(prompt: String, onReply: (TextReply) -> Unit): TextReply = runBlocking {
                    val text = StringBuilder()
                    val reasoning = StringBuilder()
                    // Collect callbacks on the owning serial worker; a callback chunk may
                    // contain several tokens, or only channel metadata.
                    // The upstream callback uses trySend: avoid losing text during UI/GC
                    // stalls. Each request is bounded by its native token budget.
                    conversation.sendMessageAsync(prompt).buffer(Channel.UNLIMITED).collect { message ->
                        val delta = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        val thought = if (thinking) message.channels[QwenThinking.channel].orEmpty() else ""
                        if (delta.isNotEmpty() || thought.isNotEmpty()) {
                            text.append(delta)
                            reasoning.append(thought)
                            onReply(TextReply(text.toString(), reasoning.toString()))
                        }
                    }
                    if (thinking) TextReply(text.toString().trim(), reasoning.toString().trim())
                    else TextReply(text.toString())
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
