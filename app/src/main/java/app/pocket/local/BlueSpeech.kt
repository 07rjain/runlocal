package app.pocket.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.system.Os
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

internal object BlueG2p {
    init { System.loadLibrary("pocket_blue") }
    external fun open(directory: String): Long
    external fun phonemize(handle: Long, text: String): String
    external fun close(handle: Long)
}

internal class BluePronunciation(directory: File) : AutoCloseable {
    private val handle: Long
    init {
        Os.setenv("POLYG2P_EN_MODEL", "large", true)
        handle = BlueG2p.open(directory.absolutePath)
    }
    fun pronounce(text: String): String = BlueG2p.phonemize(handle, text)
    override fun close() = BlueG2p.close(handle)
}

internal class BlueInput(val name: String, val shape: LongArray, val bytes: ByteArray, val longs: Boolean)
internal class BlueOutput(val shape: LongArray, val bytes: ByteArray)

internal class BlueGraph(path: File, val environment: OrtEnvironment, options: OrtSession.SessionOptions) : AutoCloseable {
    private val session = environment.createSession(path.absolutePath, options)
    fun infer(values: Array<BlueInput>): BlueOutput {
        val tensors = mutableMapOf<String, OnnxTensor>()
        try {
            for (value in values) {
                val buffer = ByteBuffer.wrap(value.bytes).order(ByteOrder.LITTLE_ENDIAN)
                tensors[value.name] = if (value.longs)
                    OnnxTensor.createTensor(environment, buffer.asLongBuffer(), value.shape)
                else OnnxTensor.createTensor(environment, buffer.asFloatBuffer(), value.shape)
            }
            session.run(tensors).use { result ->
                val tensor = result[0] as OnnxTensor
                val floats = tensor.floatBuffer
                require(floats.remaining() in 1..4_000_000) { "BlueV3 returned an invalid tensor size." }
                val bytes = ByteBuffer.allocate(floats.remaining() * 4).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(floats)
                return BlueOutput((tensor.info as TensorInfo).shape, bytes.array())
            }
        } finally { tensors.values.forEach { it.close() } }
    }
    override fun close() = session.close()
}

internal class BlueSpeechBackend(private val context: Context) : SpeechBackend {
    override fun synthesize(spec: SpeechModelSpec, voice: String, text: String, output: File,
                            cancellation: SpeechCancellation): SpeechSynthesisResult {
        if (cancellation.isCancelled()) throw CancellationException("Speech cancelled.")
        val started = System.nanoTime()
        val directory = SpeechModelStore(context).modelDirectory(spec)
        val module = localSpeechPython(context).getModule("runlocal_blue")
        val environment = OrtEnvironment.getEnvironment()
        return BluePronunciation(directory).use { pronunciation ->
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                val graphs = mutableListOf<BlueGraph>()
                try {
                    for (graph in listOf("duration", "encoder", "flow", "vocoder")) {
                        if (cancellation.isCancelled()) throw CancellationException("Speech cancelled.")
                        graphs.add(BlueGraph(File(directory, "$graph.onnx"), environment, options))
                    }
                    val loaded = System.nanoTime()
                    val frames = module.callAttr("android_synthesize", directory.absolutePath, text, output.absolutePath,
                        pronunciation, graphs.toTypedArray(), cancellation).toLong()
                    SpeechSynthesisResult((loaded - started) / 1_000_000, (System.nanoTime() - loaded) / 1_000_000, frames, 44100)
                } finally { graphs.asReversed().forEach { it.close() } }
            }
        }
    }
}
