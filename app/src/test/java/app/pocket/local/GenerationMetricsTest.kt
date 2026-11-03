package app.pocket.local

import org.junit.Assert.*
import org.junit.Test

class GenerationMetricsTest {
    @Test fun firstTextIncludesLoadingButDoesNotCountEmptyChunksOrResetOnLaterChunks() {
        var now = 0L
        val timer = GenerationTimer("CPU only") { now }
        now = 500_000_000; timer.loadMs = timer.elapsed()
        assertNull(timer.progress("Preparing prompt…").firstTextMs)
        now = 700_000_000; assertNull(timer.progress("Generating…", "").firstTextMs)
        now = 800_000_000; assertEquals(800.0, timer.progress("Generating…", "Hello").firstTextMs)
        now = 1_400_000_000; assertEquals(800.0, timer.progress("Generating…", "Hello there").firstTextMs)
        val result = timer.finish(nativeDecodeMetrics(30, 50.0, .3))
        assertEquals(500.0, result.loadMs, 0.0)
        assertEquals(1400.0, result.totalMs, 0.0)
        assertEquals(800.0, result.firstTextMs)
        assertEquals(300.0, result.firstTokenMs)
        assertEquals(30, result.outputTokens)
        assertEquals(50.0, result.tokensPerSecond)
    }
    @Test fun missingNativeMeasurementsAreNotReplacedByWordOrChunkEstimates() {
        val timer = GenerationTimer("Use GPU") { 0L }
        timer.progress("Generating…", "Many words are not model tokens")
        val result = timer.finish(null)
        assertNull(result.outputTokens); assertNull(result.tokensPerSecond); assertNull(result.firstTokenMs)
        assertTrue(result.caption().contains("Speed unavailable"))
    }
    @Test fun rejectsInvalidRatesAndTimingIncludingZeroTokenResults() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) assertNull(nativeDecodeMetrics(2, value, .1).tokensPerSecond)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, Double.MAX_VALUE)) assertNull(nativeDecodeMetrics(2, 10.0, value).firstTokenMs)
        assertNull(nativeDecodeMetrics(0, 100.0, .1).tokensPerSecond)
        assertNull(nativeDecodeMetrics(0, 100.0, .1).firstTokenMs)
    }
}
