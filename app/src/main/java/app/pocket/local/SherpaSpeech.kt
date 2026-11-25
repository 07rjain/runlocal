package app.pocket.local

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

internal class SherpaTtsBackend(private val store: SpeechModelStore) : SpeechBackend {
    override fun synthesize(spec: SpeechModelSpec, voice: String, text: String, output: File,
                            cancellation: SpeechCancellation): SpeechSynthesisResult {
        val started = System.nanoTime()
        val directory = store.modelDirectory(spec)
        fun path(name: String) = File(directory, name).absolutePath
        val config = OfflineTtsModelConfig(numThreads = 2)
        when (RecipeAdapter.forBackend(spec.backend)) {
            RecipeAdapter.KOKORO -> config.kokoro = OfflineTtsKokoroModelConfig(
                model = path("model.onnx"), voices = path("voices.bin"), tokens = path("tokens.txt"),
                lexicon = path("lexicon.txt"), lang = spec.language.ifBlank { "en" })
            RecipeAdapter.PIPER -> config.vits = OfflineTtsVitsModelConfig(
                model = path("model.onnx"), tokens = path("tokens.txt"), lexicon = path("lexicon.txt"))
            else -> error("Unsupported Sherpa voice recipe.")
        }
        if (cancellation.isCancelled()) throw CancellationException("Speech cancelled.")
        val engine = OfflineTts(config = OfflineTtsConfig(model = config))
        try {
            val loaded = System.nanoTime()
            val speaker = voice.toInt()
            require(speaker in 0 until engine.numSpeakers().coerceAtLeast(1)) { "This speaker is absent from the model." }
            val audio = engine.generateWithCallback(text, sid = speaker) { if (cancellation.isCancelled()) 0 else 1 }
            if (cancellation.isCancelled()) throw CancellationException("Speech cancelled.")
            require(audio.sampleRate in 8_000..96_000 && audio.samples.isNotEmpty() && audio.samples.all(Float::isFinite)) {
                "The model did not produce valid audio."
            }
            check(audio.save(output.absolutePath)) { "Could not save the generated audio." }
            return SpeechSynthesisResult((loaded - started) / 1_000_000, (System.nanoTime() - loaded) / 1_000_000,
                audio.samples.size.toLong(), audio.sampleRate)
        } finally {
            engine.release()
        }
    }
}

internal fun sherpaTranscribe(spec: SpeechModelSpec, directory: File, input: File, cancelled: () -> Boolean): String {
    fun path(name: String) = File(directory, name).absolutePath
    val model = OfflineModelConfig(tokens = path("tokens.txt"), numThreads = 2)
    when (RecipeAdapter.forBackend(spec.backend)) {
        RecipeAdapter.WHISPER -> model.whisper = OfflineWhisperModelConfig(encoder = path("encoder.onnx"),
            decoder = path("decoder.onnx"), language = spec.language.ifBlank { "en" })
        RecipeAdapter.ZIPFORMER -> model.transducer = OfflineTransducerModelConfig(encoder = path("encoder.onnx"),
            decoder = path("decoder.onnx"), joiner = path("joiner.onnx"))
        else -> error("Unsupported transcription recipe.")
    }
    if (cancelled()) throw CancellationException()
    val (rate, samples) = readMonoWav(input)
    if (cancelled()) throw CancellationException()
    val engine = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = model))
    try {
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(samples, rate)
            engine.decode(stream)
            if (cancelled()) throw CancellationException()
            return engine.getResult(stream).text.trim()
        } finally { stream.release() }
    } finally { engine.release() }
}

internal fun readMonoWav(file: File): Pair<Int, FloatArray> = RandomAccessFile(file, "r").use { input ->
    val error = "Choose a mono 16-bit PCM WAV up to two minutes long."
    require(input.length() in 44..(24 * MIB)) { error }
    require(input.readInt() == 0x52494646) { error } // RIFF
    val riffEnd = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt())) + 8
    require(riffEnd in 44..input.length() && input.readInt() == 0x57415645) { error } // WAVE
    var rate = 0
    var dataOffset = 0L
    var dataLength = 0L
    while (input.filePointer + 8 <= riffEnd) {
        val id = input.readInt()
        val size = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
        val start = input.filePointer
        require(start + size <= riffEnd) { error }
        when (id) {
            0x666d7420 -> { // fmt
                require(size >= 16 && rate == 0) { error }
                val format = ByteBuffer.wrap(ByteArray(16).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
                require(format.short.toInt() == 1 && format.short.toInt() == 1) { error }
                rate = format.int
                require(rate in 8_000..96_000 && format.int == rate * 2 &&
                    format.short.toInt() == 2 && format.short.toInt() == 16) { error }
            }
            0x64617461 -> { // data
                require(dataOffset == 0L)
                dataOffset = start
                dataLength = size
            }
        }
        input.seek(start + size + (size and 1))
    }
    require(rate > 0 && dataOffset > 0 && dataLength in 2..(rate.toLong() * 120 * 2) && dataLength % 2 == 0L) { error }
    input.seek(dataOffset)
    val bytes = ByteArray(dataLength.toInt()).also(input::readFully)
    val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    rate to FloatArray(shorts.remaining()) { shorts.get().toFloat() / 32768f }
}
