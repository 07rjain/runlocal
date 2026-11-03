package app.pocket.local

/** Loaded only in the :image worker process. No model weights are bundled. */
object NativeDiffusion {
    init { System.loadLibrary("pocket_diffusion") }
    external fun systemInfo(): String
    external fun generate(values: Array<String>, width: Int, height: Int, steps: Int, seed: Long,
                          cfg: Float, loraScale: Float, strength: Float, listener: NativeProgressListener)
}

interface NativeProgressListener { fun onProgress(step: Int, total: Int) }
