package app.pocket.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavRecorderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun recordedHeaderAndSamplesMatchTranscriptionInput() {
        val file = temporary.newFile()
        val pcm = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(Short.MIN_VALUE).putShort(0).putShort(Short.MAX_VALUE)
        file.writeBytes(monoPcmWavHeader(16_000, 3) + pcm.array())

        val (rate, samples) = readMonoWav(file)

        assertEquals(16_000, rate)
        assertArrayEquals(floatArrayOf(-1f, 0f, Short.MAX_VALUE / 32768f), samples, 0f)
    }

    @Test fun twoMinuteLimitMatchesTranscriptionReader() {
        val file = temporary.newFile()
        val rate = 8_000
        val limit = rate.toLong() * TRANSCRIPTION_MAX_SECONDS

        fun writeWav(frames: Long) = RandomAccessFile(file, "rw").use {
            it.write(monoPcmWavHeader(rate, frames))
            it.setLength(44 + frames * 2)
        }

        writeWav(limit)
        assertEquals(limit.toInt(), readMonoWav(file).second.size)
        writeWav(limit + 1)
        assertThrows(IllegalArgumentException::class.java) { readMonoWav(file) }
    }
}
