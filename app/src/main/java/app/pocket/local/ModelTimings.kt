package app.pocket.local

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException

/** Last successful on-device timings. Only numeric durations are persisted. */
internal class ModelTimings(context: Context) {
    private val directory = File(context.filesDir, "model-timings")

    // OCR writes in its own process. Read only the atomically replaced base file.
    fun last(key: String): Long? = try {
        file(key).takeIf(File::isFile)?.readText()?.toLongOrNull()?.takeIf { it >= 0 }
    } catch (error: IOException) {
        Log.w("RunLocal", "Could not read model timing", error)
        null
    }

    fun record(key: String, elapsedMs: Long) {
        try {
            val atomic = AtomicFile(file(key))
            val output = atomic.startWrite()
            try {
                output.write(elapsedMs.toString().toByteArray(Charsets.US_ASCII))
                atomic.finishWrite(output)
            } catch (error: IOException) {
                atomic.failWrite(output)
                throw error
            }
        } catch (error: IOException) {
            Log.w("RunLocal", "Could not save model timing", error)
        }
    }

    private fun file(key: String): File {
        require(key.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]+")))
        return File(directory, "$key.ms")
    }

    companion object {
        private val sha256 = Regex("[a-f0-9]{64}")
        fun isCatalogFile(role: ModelRole, key: String): Boolean {
            val prefix = "${role.name}-sha256-"
            return key.startsWith(prefix) && sha256.matches(key.removePrefix(prefix))
        }
    }
}
