package app.pocket.local

internal enum class RecognitionInput { FILE, CAMERA, RECORDING }

internal enum class RecognitionMode(val kind: LibraryKind, val task: ModelTask, val prefix: String) {
    OCR(LibraryKind.OCR, ModelTask.OCR, "ocr:"),
    TRANSCRIPTION(LibraryKind.TRANSCRIPTION, ModelTask.DICTATE, "transcription:");

    fun modelKey(id: String) = prefix + id

    fun sourceLabel(input: RecognitionInput): String? = when (input) {
        RecognitionInput.FILE -> if (this == OCR) "Image" else "Audio file"
        RecognitionInput.CAMERA -> "Photo".takeIf { this == OCR }
        RecognitionInput.RECORDING -> "Recording".takeIf { this == TRANSCRIPTION }
    }

    companion object {
        const val MIRAGE_KEY = "ocr:mirage"
        fun fromModel(key: String) = entries.firstOrNull { key.startsWith(it.prefix) && key.length > it.prefix.length }
        fun fromTask(task: ModelTask?) = entries.firstOrNull { it.task == task }
    }
}
