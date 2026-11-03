package app.pocket.local

/** Explicit user choice. GPU errors never silently change the requested backend. */
enum class ProcessingBackend(val title: String) {
    CPU("CPU only"), GPU("Use GPU");
    companion object {
        fun fromPreference(value: String?): ProcessingBackend = entries.firstOrNull { it.name == value } ?: CPU
    }
}

data class TextFailure(val summary: String, val action: String, val details: String) {
    val message: String get() = "$summary\n\n$action"
}

fun explainTextFailure(error: Throwable, backend: ProcessingBackend): TextFailure {
    val details = generateSequence(error) { it.cause }.take(5).mapNotNull { it.message }.distinct().joinToString("\n").ifBlank { error.javaClass.simpleName }
    val lower = details.lowercase()
    val (summary, action) = when {
        listOf("out of memory", "not enough memory", "bad_alloc", "cannot allocate", "allocation failed").any { it in lower } ->
            "There is not enough memory to run this model." to "Close other apps or choose a smaller model. GPU processing also uses phone memory."
        listOf("context length", "maximum number of tokens", "max_num_tokens", "context window", "kv cache is full", "input is too long").any { it in lower } ->
            "This conversation exceeds the model’s context limit." to "Start a new chat or send a shorter message. Your saved conversation is kept."
        backend == ProcessingBackend.GPU && listOf("opencl", "gpu", "delegate", "egl", "gl_", "vulkan").any { it in lower } ->
            "GPU processing could not start or finish on this phone." to "Choose CPU only below the model selector and retry. GPU support depends on the model and phone driver. RunLocal has not switched to CPU automatically."
        listOf("failed to load model from buffer", "unimplemented", "unsupported", "invalid model", "model format").any { it in lower } ->
            "The runtime could not read this model or execute one of its operations." to "This build includes LiteRT-LM ${TextRuntimeInfo.VERSION}. The model must support that runtime and ${backend.title}. If the file is incomplete, add its Hugging Face link again. A different prompt will not fix a model-loading error."
        backend == ProcessingBackend.GPU ->
            "The model failed while using GPU processing." to "Try CPU only below the model selector. RunLocal has not switched processors automatically."
        else -> "The model could not finish on the CPU." to "Check that the model supports CPU execution and fits the phone’s memory. Your conversation is kept; you can select another model or retry."
    }
    return TextFailure(summary, action, details)
}

object TextRuntimeInfo { const val VERSION = "0.18.1" }
