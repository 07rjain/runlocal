package app.pocket.local

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

internal val recognitionWorker = Executors.newSingleThreadExecutor()

internal class RecognitionEngine(private val context: Context) {
    private val recipes = SpeechModelStore(context)

    fun transcribeRecording(audio: File, spec: SpeechModelSpec, cancel: AtomicBoolean): String {
        checkRecipe(spec)
        return sherpaTranscribe(spec, recipes.modelDirectory(spec), audio, cancel::get)
    }

    fun transcribe(uri: Uri, spec: SpeechModelSpec, cancel: AtomicBoolean): String {
        checkRecipe(spec)
        val audio = File.createTempFile("transcription-", ".wav", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                audio.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        if (cancel.get()) throw CancellationException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes += read
                        require(bytes <= 24 * MIB) { "Choose a WAV file under 24 MB." }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("The audio file could not be opened.")
            return sherpaTranscribe(spec, recipes.modelDirectory(spec), audio, cancel::get)
        } finally { audio.delete() }
    }

    private fun checkRecipe(spec: SpeechModelSpec) {
        check(recipes.installed(spec)) { "Download this model in Models before using it." }
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        check(!memory.lowMemory && memory.availMem > spec.memoryBytes) { "Close other apps to free memory before using this model." }
    }

    fun readImage(uri: Uri, cancel: AtomicBoolean, spec: SpeechModelSpec?): String {
        if (spec != null) checkRecipe(spec)
        else check(MirageModel.installed(context)) { "Install Mirage before reading an image." }
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        check(!memory.lowMemory && memory.availMem > MirageModel.memoryMinimum) { "Close other apps to free memory before reading this image." }

        val encoded = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (cancel.get()) throw CancellationException()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 24 * 1024 * 1024) { "Choose an image under 24 MB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("The selected image could not be opened.")

        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
            val largest = maxOf(info.size.width, info.size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (largest > MirageNative.maxEdge) {
                val scale = MirageNative.maxEdge.toDouble() / largest
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
        }
        return try {
            if (spec != null) {
                require(spec.backend == RecipeAdapter.TESSERACT.backend)
                val tess = TessBaseAPI()
                try {
                    check(tess.init(recipes.modelDirectory(spec).absolutePath, spec.language)) {
                        "Could not open this Tesseract language pack."
                    }
                    if (cancel.get()) throw CancellationException()
                    tess.setImage(bitmap)
                    return tess.getUTF8Text().orEmpty().trim()
                } finally { tess.recycle() }
            }
            val width = bitmap.width
            val height = bitmap.height
            val rgb = ByteArray(width * height * 3)
            val row = IntArray(width)
            var offset = 0
            for (y in 0 until height) {
                if (cancel.get()) throw CancellationException()
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (pixel in row) {
                    val alpha = pixel ushr 24
                    for (shift in RGB_SHIFTS) {
                        val channel = (pixel ushr shift) and 255
                        // Composite transparency onto white so transparent pixels do not become black text.
                        rgb[offset++] = (255 - (255 - channel) * alpha / 255).toByte()
                    }
                }
            }
            if (cancel.get()) throw CancellationException()
            MirageNative.read(MirageModel.file(context), rgb, width, height)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object { val RGB_SHIFTS = intArrayOf(16, 8, 0) }
}
