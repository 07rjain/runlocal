package app.pocket.local

import android.content.Context
import android.system.Os
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SpeechCancellation {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    fun isCancelled(): Boolean = cancelled.get()
}

data class SpeechSynthesisResult(val loadMs: Long, val generationMs: Long, val frames: Long, val sampleRate: Int)

/** Implement a new adapter here for each validated model family; shared UI and playback stay unchanged. */
interface SpeechBackend {
    fun synthesize(spec: SpeechModelSpec, voice: String, text: String, output: File,
                   cancellation: SpeechCancellation): SpeechSynthesisResult
}

object SpeechBackends {
    fun create(context: Context, id: String): SpeechBackend = when (id) {
        SpeechModelCatalog.WOOF_BACKEND -> WoofSpeechBackend(context)
        SpeechModelCatalog.KITTEN_PICO_BACKEND -> KittenPicoBackend(SpeechModelStore(context))
        RecipeAdapter.KOKORO.backend, RecipeAdapter.PIPER.backend -> SherpaTtsBackend(SpeechModelStore(context))
        RecipeAdapter.BLUE.backend -> BlueSpeechBackend(context)
        else -> error("This speech runtime is not bundled in this app version.")
    }
}

private class KittenPicoBackend(private val store: SpeechModelStore) : SpeechBackend {
    override fun synthesize(spec: SpeechModelSpec, voice: String, text: String, output: File,
                            cancellation: SpeechCancellation): SpeechSynthesisResult =
        KittenPico.synthesize(store.modelDirectory(spec), voice, text, output, cancellation::isCancelled)
}

private class WoofSpeechBackend(private val context: Context) : SpeechBackend {
    override fun synthesize(spec: SpeechModelSpec, voice: String, text: String, output: File,
                            cancellation: SpeechCancellation): SpeechSynthesisResult {
        // Called only on the speech process's serial worker. Python is never started in the UI process.
        val python = localSpeechPython(context)
        val weights = SpeechModelStore(context).file(spec, spec.files.single()).absolutePath
        val values = python.getModule("runlocal_woof")
            .callAttr("synthesize", weights, voice, text, output.absolutePath, cancellation).asList()
        return SpeechSynthesisResult(values[0].toLong(), values[1].toLong(), values[2].toLong(), 24000)
    }
}

internal fun localSpeechPython(context: Context): Python {
    if (!Python.isStarted()) {
        Os.setenv("OPENBLAS_NUM_THREADS", "1", true)
        Os.setenv("OMP_NUM_THREADS", "1", true)
        Python.start(AndroidPlatform(context))
    }
    return Python.getInstance()
}
