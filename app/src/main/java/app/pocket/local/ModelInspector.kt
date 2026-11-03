package app.pocket.local

import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile

enum class ModelRole(val label: String) {
    CHECKPOINT("Complete SD 1.x checkpoint"), DIFFUSION("MOSS diffusion model"),
    ENCODER("Qwen3.5 text encoder"), VAE("VAE / image decoder"),
    TOKENIZER("Tokenizer JSON"), LORA("LoRA adapter"), TEXT("LiteRT-LM text model")
}

/** Inspect containers before allowing native code to touch a user's download. */
object ModelInspector {
    fun validate(file: File, displayName: String, role: ModelRole) {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(file.length() >= 8) { "This file is empty or incomplete." }
        if (role == ModelRole.TEXT) {
            require(extension == "litertlm") { "Choose a compatible .litertlm text model." }
            val header = ByteArray(8)
            file.inputStream().use { require(it.read(header) == 8 && String(header, Charsets.US_ASCII) == "LITERTLM") { "This is not a LiteRT-LM container." } }
            return
        }
        if (role == ModelRole.TOKENIZER) {
            require(extension == "json" && file.length() <= 32L * 1024 * 1024) { "Select the model's tokenizer.json, under 32 MB." }
            val json = JsonParser.parseString(file.readText()).asJsonObject
            require(json.has("model") && json.get("model").isJsonObject) { "This JSON is not a tokenizer file." }
            return
        }
        require(extension in listOf("gguf", "safetensors")) { "Import .gguf or .safetensors weights. A repository page, ZIP, or Git LFS pointer is not a model file." }
        RandomAccessFile(file, "r").use { input ->
            if (extension == "gguf") {
                require(role != ModelRole.LORA) { "Import this adapter as a .safetensors file." }
                val signature = ByteArray(4).also { input.readFully(it) }
                require(String(signature, Charsets.US_ASCII) == "GGUF") { "This is not a GGUF container. Download the actual model file." }
                val version = Integer.reverseBytes(input.readInt())
                require(version == 2 || version == 3) { "This GGUF container version is not supported." }
                require(file.length() >= 24) { "The GGUF header is incomplete." }
            } else {
                require(role != ModelRole.ENCODER) { "Use a Qwen3.5 GGUF text encoder for this MOSS pipeline." }
                val length = java.lang.Long.reverseBytes(input.readLong())
                require(length in 2..(16L * 1024 * 1024) && length < file.length() - 8) { "Invalid Safetensors header. The file may be incomplete or a Git LFS pointer." }
                val header = ByteArray(length.toInt()).also { input.readFully(it) }
                val json = JsonParser.parseString(header.toString(Charsets.UTF_8)).asJsonObject
                val tensors = json.entrySet().filter { it.key != "__metadata__" }
                require(tensors.isNotEmpty()) { "This Safetensors file contains no weights." }
                val payload = file.length() - length - 8
                tensors.forEach { (_, value) ->
                    val tensor = value.asJsonObject
                    require(tensor.has("dtype") && tensor.has("shape")) { "Invalid tensor metadata." }
                    val offsets = tensor.getAsJsonArray("data_offsets")
                    require(offsets.size() == 2 && offsets[0].asLong >= 0 && offsets[1].asLong >= offsets[0].asLong && offsets[1].asLong <= payload) { "Tensor data is truncated or has invalid offsets." }
                }
                val adapter = tensors.any { (key, _) -> key.contains("lora_", true) || key.contains(".lora", true) }
                require(role != ModelRole.LORA || adapter) { "No LoRA tensors were found. Choose an adapter file." }
                require(role == ModelRole.LORA || !adapter) { "This is a LoRA adapter. Import it in the optional adapter slot, alongside a complete base model." }
            }
        }
    }
}
