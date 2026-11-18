package app.pocket.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MirageOcrTest {
    @Test fun assemblesMisorderedWordRegionsIntoReadableLines() {
        val response = """
            {"text":[
              {"string":"7","bbox":[559,701,621,830]},
              {"string":"OCR","bbox":[631,118,859,299]},
              {"string":"09:45","bbox":[483,413,748,565]},
              {"string":"AT","bbox":[354,420,472,563]},
              {"string":"RUNLOCAL","bbox":[57,113,610,304]},
              {"string":"PLATFORM","bbox":[57,681,586,847]},
              {"string":"TRAIN","bbox":[72,420,346,559]}
            ]}
        """.trimIndent()
        assertEquals("RUNLOCAL OCR\nTRAIN AT 09:45\nPLATFORM 7", MirageTranscript.format(response))
    }

    @Test fun distinguishesNoTextFromAMalformedRuntimeReply() {
        assertEquals("", MirageTranscript.format("""{"text":[]}"""))
        assertEquals("ARRÉT", MirageTranscript.format("""{"text":[{"string":"ARRÉT","bbox":[364,296,660,379]}]}"""))
        assertThrows(IllegalStateException::class.java) { MirageTranscript.format("""{"scene":{}}""") }
        assertThrows(IllegalArgumentException::class.java) {
            MirageTranscript.format("""{"text":[{"string":"Hi","bbox":[8,80,10,6]}]}""")
        }
    }

    @Test fun acceptsOnlyThePinnedMirageRecipeFromHuggingFace() {
        assertTrue(MirageModel.matchesLink(MirageModel.modelUrl))
        assertTrue(MirageModel.matchesLink(MirageModel.url))
        assertFalse(MirageModel.matchesLink(MirageModel.modelUrl + "/resolve/main/mirage.safetensors"))
        assertFalse(MirageModel.matchesLink(MirageModel.modelUrl + "/tree/unreviewed"))
        assertFalse(MirageModel.matchesLink("https://example.com/Cactus-Compute/mirage"))
    }
}
