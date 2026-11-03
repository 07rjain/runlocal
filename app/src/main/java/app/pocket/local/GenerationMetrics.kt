package app.pocket.local

import java.util.Locale

data class DecodeMetrics(val tokens: Int, val tokensPerSecond: Double?, val firstTokenMs: Double?)
data class GenerationMetrics(
    val backend: String,
    val loadMs: Double,
    val firstTextMs: Double?,
    val totalMs: Double,
    val outputTokens: Int?,
    val tokensPerSecond: Double?,
    val firstTokenMs: Double?
) {
    fun caption(): String = listOfNotNull(
        tokensPerSecond?.let { String.format(Locale.US, "%.1f tok/s", it) } ?: "Speed unavailable",
        firstTokenMs?.let { "TTFT ${seconds(it)}" },
        "Total ${seconds(totalMs)}"
    ).joinToString("  ·  ")
    fun details(): String = "Processor: $backend\n" +
        "Decode speed: ${tokensPerSecond?.let { String.format(Locale.US, "%.1f tokens/sec", it) } ?: "Not reported by runtime"}\n" +
        "Output tokens: ${outputTokens ?: "Not reported by runtime"}\n" +
        "First token (runtime): ${firstTokenMs?.let { seconds(it) } ?: "Not reported by runtime"}\n" +
        "First text received (including model load): ${firstTextMs?.let { seconds(it) } ?: "No visible text"}\n" +
        "Model/session load: ${seconds(loadMs)}\nTotal: ${seconds(totalMs)}\n\n" +
        "Speed and token counts come from LiteRT-LM’s last decode, not word or character estimates. Runtime TTFT and decode speed exclude app-side model loading. First text received includes loading and prompt preparation. A reused session has no model load."
}

data class GenerationProgress(val stage: String, val text: String = "", val elapsedMs: Double = 0.0, val firstTextMs: Double? = null, val metrics: GenerationMetrics? = null)

internal fun nativeDecodeMetrics(tokens: Int, speed: Double, firstTokenSeconds: Double): DecodeMetrics = DecodeMetrics(
    tokens.coerceAtLeast(0), speed.takeIf { tokens > 0 && it.isFinite() && it > 0 },
    firstTokenSeconds.takeIf { tokens > 0 && it.isFinite() && it >= 0 }?.times(1000)?.takeIf { it.isFinite() }
)
internal fun seconds(ms: Double): String = String.format(Locale.US, "%.2f s", ms / 1000)

/** Monotonic app-side timing; visible chunks are never counted as model tokens. */
internal class GenerationTimer(private val backend: String, private val now: () -> Long = System::nanoTime) {
    private val start = now()
    private var firstText: Double? = null
    var loadMs = 0.0
    fun elapsed() = ((now() - start) / 1_000_000.0).coerceAtLeast(0.0)
    fun progress(stage: String, text: String = ""): GenerationProgress {
        val elapsed = elapsed()
        if (text.isNotEmpty() && firstText == null) firstText = elapsed
        return GenerationProgress(stage, text, elapsed, firstText)
    }
    fun finish(native: DecodeMetrics?) = GenerationMetrics(backend, loadMs, firstText, elapsed(),
        native?.tokens?.takeIf { it > 0 }, native?.tokensPerSecond, native?.firstTokenMs)
}
