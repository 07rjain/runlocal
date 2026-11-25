package app.pocket.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/** CPU inference for the pinned English Pico graph and its published voice packs. */
internal object KittenPico {
    private const val RATE = 24_000
    private const val STYLE_WIDTH = 256
    private const val STYLE_ROWS = 510
    private const val CHUNK_LIMIT = 220
    private const val NPY_MAGIC = "\u0093NUMPY"
    private val words = Regex("[a-z]+(?:'[a-z]+)*|[0-9]|[.,!?;:…—]")
    private val digits = "zero one two three four five six seven eight nine".split(' ')
    private val letters = listOf("ˈA", "bˈi", "sˈi", "dˈi", "ˈi", "ˈɛf", "ʤˈi", "ˈAʧ", "ˈI", "ʤˈA", "kˈA", "ˈɛl",
        "ˈɛm", "ˈɛn", "ˈO", "pˈi", "kjˈu", "ˈɑɹ", "ˈɛs", "tˈi", "jˈu", "vˈi", "dˈʌbᵊl jˈu", "ˈɛks", "wˈI", "zˈi")

    fun synthesize(directory: File, voice: String, text: String, output: File,
                   cancelled: () -> Boolean): SpeechSynthesisResult {
        fun checkCancellation() { if (cancelled()) throw CancellationException("Speech cancelled.") }
        checkCancellation()
        val started = System.nanoTime()
        val tokens = tokenize(directory, text)
        val styles = voiceStyles(File(directory, "voices_en.npz"), voice)
        val environment = OrtEnvironment.getEnvironment()
        var frames = 0L
        val loaded: Long
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            environment.createSession(File(directory, "kitten_tts_2_pico_en_int4.onnx").absolutePath, options).use { session ->
                loaded = System.nanoTime()
                RandomAccessFile(output, "rw").use { wav ->
                    wav.setLength(0)
                    wav.seek(44)
                    for ((index, ids) in tokens.withIndex()) {
                        checkCancellation()
                        val offset = (ids.size - 1) * STYLE_WIDTH
                        val pcm = generate(environment, session, ids, styles, offset, index == 0)
                        checkCancellation()
                        wav.write(pcm)
                        frames += pcm.size / 2
                    }
                    wav.seek(0)
                    wav.write(wavHeader(frames))
                }
            }
        }
        return SpeechSynthesisResult((loaded - started) / 1_000_000, (System.nanoTime() - loaded) / 1_000_000, frames, RATE)
    }

    private fun tokenize(directory: File, text: String): List<LongArray> {
        val vocab = File(directory, "vocab.json").reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        val dictionary = File(directory, "g2p/cmu_misaki_us.json").reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        val input = Normalizer.normalize(text.replace('’', '\'').replace('-', ' '), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        require(input.any { it in 'a'..'z' || it in '0'..'9' }) { "Enter English text for this voice." }
        require(input.none { it.isLetter() && it !in 'a'..'z' }) { "This voice currently reads English only." }
        fun lookup(word: String): String? = dictionary.get(word)?.asString
        val end = vocab.get("…").asLong
        val space = vocab.get(" ").asLong
        val result = mutableListOf<LongArray>()
        val chunk = mutableListOf<Long>()
        fun flush() {
            if (chunk.isEmpty()) return
            result.add((listOf(0L) + chunk + listOf(end, 0L)).toLongArray())
            chunk.clear()
        }
        words.findAll(input).forEach { match ->
            val value = match.value
            val punctuation = value.length == 1 && !value[0].isLetterOrDigit()
            val phonemes = when {
                punctuation -> value
                value[0].isDigit() -> checkNotNull(lookup(digits[value[0] - '0']))
                else -> lookup(value) ?: value.asSequence().filter { it in 'a'..'z' }.joinToString(" ") { letter ->
                    letters[letter - 'a']
                }
            }
            val ids = phonemes.mapNotNull { vocab.get(it.toString())?.asLong }
            if (ids.isEmpty()) return@forEach
            // A very long unknown word expands to spelled-out letters. Keep its input bounded too.
            for (piece in ids.chunked(CHUNK_LIMIT)) {
                if (chunk.size + piece.size + 1 > CHUNK_LIMIT) flush()
                if (chunk.isNotEmpty() && !punctuation) chunk.add(space)
                chunk.addAll(piece)
            }
            if (chunk.size >= CHUNK_LIMIT / 2 && value in listOf(".", "!", "?", "…")) flush()
        }
        flush()
        require(result.isNotEmpty()) { "No speakable text was found." }
        return result
    }

    private fun voiceStyles(archive: File, voice: String): FloatArray = ZipFile(archive).use { zip ->
        val entry = checkNotNull(zip.getEntry("$voice.npy")) { "The selected Kitten voice is missing." }
        val dataBytes = STYLE_ROWS * STYLE_WIDTH * Float.SIZE_BYTES
        require(entry.size in dataBytes.toLong()..(dataBytes + 1024L)) { "Invalid Kitten voice size." }
        val data = ByteArray(entry.size.toInt())
        DataInputStream(zip.getInputStream(entry)).use { input ->
            input.readFully(data)
            require(input.read() == -1)
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(6).also { buffer.get(it) }.toString(Charsets.ISO_8859_1)
        require(magic == NPY_MAGIC && buffer.get().toInt() == 1 && buffer.get().toInt() == 0)
        val headerSize = buffer.short.toInt() and 0xffff
        require(headerSize in 1..1024 && buffer.remaining() == headerSize + dataBytes)
        val header = ByteArray(headerSize).also { buffer.get(it) }.toString(Charsets.US_ASCII)
        require("'descr': '<f4'" in header && "'fortran_order': False" in header && "'shape': (510, 256)" in header)
        FloatArray(STYLE_ROWS * STYLE_WIDTH).also { buffer.asFloatBuffer().get(it) }
    }

    private fun generate(env: OrtEnvironment, session: OrtSession, ids: LongArray, styles: FloatArray,
                         offset: Int, first: Boolean): ByteArray {
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(styles, offset, STYLE_WIDTH), longArrayOf(1, STYLE_WIDTH.toLong())).use { style ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1)).use { speed ->
                    session.run(mapOf("input_ids" to input, "style" to style, "speed" to speed)).use { result ->
                        val wave = (result[0] as OnnxTensor).floatBuffer
                        val durations = (result[1] as OnnxTensor).longBuffer
                        return toPcm(wave, durations, first)
                    }
                }
            }
        }
    }

    private fun toPcm(wave: FloatBuffer, durations: LongBuffer, first: Boolean): ByteArray {
        val samples = wave.remaining()
        require(samples in 1..(RATE * 180) && durations.remaining() > 0) { "Invalid audio from Kitten Pico." }
        val total = (0 until durations.limit()).sumOf { durations.get(it) }.coerceAtLeast(1)
        val perFrame = (samples.toDouble() / total).roundToInt().coerceAtLeast(1)
        val lead = if (first) 0 else ((durations.get(0) - 1).coerceAtLeast(0) * perFrame).toInt()
        val tail = ((durations.get(durations.limit() - 1) - 1).coerceAtLeast(0) * perFrame).toInt()
        val trimmed = lead + tail + perFrame < samples
        val start = if (trimmed) lead else 0
        val count = if (trimmed) samples - lead - tail else samples
        val fade = minOf(120, count / 8)
        val pcm = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) {
            val sample = wave.get(start + index)
            require(sample.isFinite()) { "Invalid audio from Kitten Pico." }
            val gain = if (fade > 1) minOf(1f, minOf(index, count - 1 - index).toFloat() / (fade - 1)) else 1f
            pcm.putShort((sample.coerceIn(-1f, 1f) * gain * 32767).roundToInt().toShort())
        }
        return pcm.array()
    }

    private fun wavHeader(frames: Long): ByteArray {
        val dataBytes = Math.toIntExact(frames * 2)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(dataBytes + 36); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }.array()
    }
}
