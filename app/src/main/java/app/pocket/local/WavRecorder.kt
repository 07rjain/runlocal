package app.pocket.local

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

internal const val TRANSCRIPTION_MAX_SECONDS = 120

internal fun monoPcmWavHeader(sampleRate: Int, frames: Long): ByteArray {
    val dataBytes = Math.toIntExact(frames * 2)
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".encodeToByteArray())
        putInt(dataBytes + 36)
        put("WAVEfmt ".encodeToByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(sampleRate)
        putInt(sampleRate * 2)
        putShort(2)
        putShort(16)
        put("data".encodeToByteArray())
        putInt(dataBytes)
    }.array()
}

// The activity checks RECORD_AUDIO before starting this worker. Android also enforces it at open.
@SuppressLint("MissingPermission")
internal fun recordChatWav(output: File, shouldStop: () -> Boolean, isCancelled: () -> Boolean,
                           onRecording: () -> Unit = {}) {
    val rate = 16_000
    val format = AudioFormat.ENCODING_PCM_16BIT
    val channel = AudioFormat.CHANNEL_IN_MONO
    val minimumBuffer = AudioRecord.getMinBufferSize(rate, channel, format)
    check(minimumBuffer > 0) { "This microphone does not support audio recording at 16 kHz." }
    val samples = ShortArray(rate / 10)
    val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    val maxFrames = rate * TRANSCRIPTION_MAX_SECONDS
    val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC, rate, channel, format, maxOf(minimumBuffer, pcm.capacity() * 2)
    )
    try {
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Could not open the microphone." }
        if (isCancelled()) throw CancellationException()
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Could not start recording." }
        RandomAccessFile(output, "rw").use { file ->
            file.setLength(0)
            file.write(monoPcmWavHeader(rate, 0))
            var frames = 0
            while (frames < maxFrames && !shouldStop() && !isCancelled()) {
                val count = recorder.read(samples, 0, minOf(samples.size, maxFrames - frames), AudioRecord.READ_BLOCKING)
                check(count > 0) { "The microphone stopped recording. Try again." }
                pcm.clear()
                for (index in 0 until count) pcm.putShort(samples[index])
                file.write(pcm.array(), 0, count * 2)
                if (frames == 0) onRecording()
                frames += count
            }
            if (isCancelled()) throw CancellationException()
            check(frames > 0) { "No audio was recorded. Try recording again." }
            file.seek(0)
            file.write(monoPcmWavHeader(rate, frames.toLong()))
        }
    } finally {
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } finally {
            recorder.release()
        }
    }
}
