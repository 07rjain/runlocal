package app.pocket.local

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.InflaterInputStream

/** Optional host-JNI tests exercise the same wrapper and pinned engine as Android. */
class NativeDiffusionSmokeTest {
    private val progressEvents = java.util.concurrent.atomic.AtomicInteger()
    private val callback = object : NativeProgressListener {
        override fun onProgress(step: Int, total: Int) { if (step > 0 && total > 0) progressEvents.incrementAndGet() }
    }
    @Before fun nativeBuildAvailable() { assumeTrue(System.getProperty("pocket.native.enabled") == "true") }
    @Test fun runtimeLoadsAndReportsItsBackend() { assertTrue(NativeDiffusion.systemInfo().isNotBlank()) }
    @Test fun missingMossComponentsFailBeforeLoading() {
        val values = Array(11) { "" }.apply { this[0] = "moss"; this[7] = "a blue ceramic cup"; this[10] = "/tmp/pocket-unused.png" }
        val error = assertThrows(IllegalStateException::class.java) { NativeDiffusion.generate(values, 128, 128, 1, 42, 3f, 1f, 1f, callback) }
        assertTrue(error.message.orEmpty().contains("missing required"))
    }
    @Test(timeout = 600000) fun textToImageAndImageToImageWithRealWeights() {
        val path = System.getProperty("pocket.test.model")
        assumeTrue(path != null && File(path).isFile)
        val directory = kotlin.io.path.createTempDirectory("pocket-native-smoke").toFile()
        try {
            val output = File(directory, "generated.png")
            val edited = File(directory, "edited.png")
            val values = Array(11) { "" }.apply { this[0] = "sd15"; this[1] = path; this[7] = "a blue ceramic cup on a wooden table"; this[10] = output.path }
            NativeDiffusion.generate(values, 128, 128, 1, 42, 1f, 1f, 1f, callback)
            verifyPng(output)
            assertTrue("Native sampling should report progress to Kotlin", progressEvents.get() > 0)
            values[9] = output.path; values[10] = edited.path
            NativeDiffusion.generate(values, 128, 128, 2, 43, 1f, 1f, .65f, callback)
            verifyPng(edited)
            assertFalse(output.readBytes().contentEquals(edited.readBytes()))
        } finally { directory.deleteRecursively() }
    }
    private fun verifyPng(file: File) {
        val bytes = file.readBytes()
        assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.copyOfRange(0, 8))
        val input = ByteBuffer.wrap(bytes)
        assertEquals(128, input.getInt(16)); assertEquals(128, input.getInt(20))
        val compressed = ByteArrayOutputStream()
        input.position(8)
        while (input.remaining() >= 12) {
            val length = input.int
            val type = ByteArray(4).also { input.get(it) }.toString(Charsets.US_ASCII)
            require(length >= 0 && length <= input.remaining() - 4)
            val payload = ByteArray(length).also { input.get(it) }
            if (type == "IDAT") compressed.write(payload)
            input.int // chunk CRC
            if (type == "IEND") break
        }
        val pixels = InflaterInputStream(compressed.toByteArray().inputStream()).use { it.readBytes() }
        assertTrue(pixels.size >= 128 * (128 * 3 + 1))
        assertTrue(pixels.toSet().size > 1)
    }
}
