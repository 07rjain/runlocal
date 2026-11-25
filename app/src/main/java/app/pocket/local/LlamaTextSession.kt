package app.pocket.local

internal fun interface LlamaTextListener { fun onText(bytes: ByteArray) }

internal object LlamaNative {
    init { System.loadLibrary("pocket_llama") }
    external fun open(path: ByteArray): Long
    external fun generate(handle: Long, history: Array<ByteArray>, listener: LlamaTextListener): DoubleArray
    external fun close(handle: Long)
}

internal class LlamaTextSession(model: LocalModel, history: List<ChatLine>) : TextSession {
    private var handle = LlamaNative.open(model.file.absolutePath.encodeToByteArray())
    private val turns = history.mapTo(mutableListOf()) { it.text }
    private var measured: DecodeMetrics? = null

    override fun send(prompt: String) = stream(prompt) {}

    override fun stream(prompt: String, onText: (String) -> Unit): String {
        check(handle != 0L) { "The GGUF session has closed." }
        var result = ""
        val stats = LlamaNative.generate(handle, (turns + prompt).map { it.encodeToByteArray() }.toTypedArray()) { bytes ->
            result = bytes.decodeToString()
            onText(result)
        }
        measured = nativeDecodeMetrics(stats[0].toInt(), stats[1], stats[2])
        turns.add(prompt)
        turns.add(result)
        return result
    }

    override fun metrics() = measured

    override fun close() {
        if (handle != 0L) LlamaNative.close(handle)
        handle = 0
    }
}
