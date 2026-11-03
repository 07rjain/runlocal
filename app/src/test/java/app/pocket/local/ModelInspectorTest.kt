package app.pocket.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelInspectorTest {
    private fun withFile(bytes: ByteArray, test: (File) -> Unit) {
        val file = File.createTempFile("pocket-model", ".test")
        try { file.writeBytes(bytes); test(file) } finally { file.delete() }
    }
    private fun safetensors(key: String = "model.weight", end: Int = 4): ByteArray {
        val header = "{\"$key\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,$end]}}".toByteArray()
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array() + header + ByteArray(4)
    }
    @Test fun downloadedWebPageIsNotAcceptedAsWeights() = withFile("<html>Sign in to download</html>".toByteArray()) {
        assertThrows(IllegalArgumentException::class.java) { ModelInspector.validate(it, "model.safetensors", ModelRole.DIFFUSION) }
    }
    @Test fun gitLfsPointerIsRejected() = withFile("version https://git-lfs.github.com/spec/v1\noid sha256:test\nsize 4000000000\n".toByteArray()) {
        assertThrows(IllegalArgumentException::class.java) { ModelInspector.validate(it, "model.gguf", ModelRole.CHECKPOINT) }
    }
    @Test fun maliciousHeaderLengthDoesNotAllocateMemory() = withFile(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(Long.MAX_VALUE).array()) {
        assertThrows(IllegalArgumentException::class.java) { ModelInspector.validate(it, "model.safetensors", ModelRole.VAE) }
    }
    @Test fun truncatedTensorPayloadIsRejected() = withFile(safetensors(end = 4096)) {
        assertThrows(IllegalArgumentException::class.java) { ModelInspector.validate(it, "model.safetensors", ModelRole.CHECKPOINT) }
    }
    @Test fun adapterCannotMasqueradeAsBaseModel() = withFile(safetensors("transformer.layer.lora_A.weight")) {
        assertThrows(IllegalArgumentException::class.java) { ModelInspector.validate(it, "adapter.safetensors", ModelRole.DIFFUSION) }
        ModelInspector.validate(it, "adapter.safetensors", ModelRole.LORA)
    }
    @Test fun baseModelCannotMasqueradeAsAdapter() = withFile(safetensors()) {
        ModelInspector.validate(it, "base.safetensors", ModelRole.CHECKPOINT)
        assertThrows(IllegalArgumentException::class.java) { ModelInspector.validate(it, "base.safetensors", ModelRole.LORA) }
    }
    @Test fun arbitraryJsonCannotMasqueradeAsTokenizer() = withFile("{\"hello\":\"world\"}".toByteArray()) {
        assertThrows(IllegalArgumentException::class.java) { ModelInspector.validate(it, "tokenizer.json", ModelRole.TOKENIZER) }
    }
    @Test fun validTokenizerShapeIsAccepted() = withFile("{\"model\":{\"type\":\"BPE\",\"vocab\":{}}}".toByteArray()) {
        ModelInspector.validate(it, "tokenizer.json", ModelRole.TOKENIZER)
    }
    @Test fun ggufContainerVersionsAreChecked() {
        for (version in listOf(1, 2, 3, 99)) {
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).put("GGUF".toByteArray()).putInt(version).array()
            withFile(header) {
                if (version in 2..3) ModelInspector.validate(it, "weights.gguf", ModelRole.DIFFUSION)
                else assertThrows(IllegalArgumentException::class.java) { ModelInspector.validate(it, "weights.gguf", ModelRole.DIFFUSION) }
            }
        }
    }
    @Test fun textModelNeedsItsActualContainerSignature() {
        withFile("LITERTLM".toByteArray() + ByteArray(16)) { ModelInspector.validate(it, "chat.litertlm", ModelRole.TEXT) }
        withFile("<html>not a model</html>".toByteArray()) {
            assertThrows(IllegalArgumentException::class.java) { ModelInspector.validate(it, "chat.litertlm", ModelRole.TEXT) }
        }
        withFile(safetensors()) {
            assertThrows(IllegalArgumentException::class.java) { ModelInspector.validate(it, "chat.litertlm", ModelRole.TEXT) }
        }
    }

}
